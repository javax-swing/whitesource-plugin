/*
 * Copyright (C) 2010 White Source Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.whitesource.jenkins;

import hudson.*;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.whitesource.agent.api.dispatch.CheckPolicyComplianceResult;
import org.whitesource.agent.api.dispatch.UpdateInventoryResult;
import org.whitesource.agent.api.model.AgentProjectInfo;
import org.whitesource.agent.client.WhitesourceService;
import org.whitesource.agent.client.WssServiceException;
import org.whitesource.agent.report.PolicyCheckReport;
import org.whitesource.jenkins.extractor.generic.GenericOssInfoExtractor;
import org.whitesource.jenkins.extractor.maven.MavenOssInfoExtractor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;

/**
 * @author ramakrishna
 * @author Edo.Shor
 */
public class WhiteSourcePublisher extends Recorder implements SimpleBuildStep {

    /* --- Members --- */

    public static final String GLOBAL = "global";
    public static final String ENABLE_NEW = "enableNew";
    public static final String ENABLE_ALL = "enableAll";
    public static final String JOB_FORCE_UPDATE = "forceUpdate";
    public static final int DEFAULT_TIMEOUT = 60;

    private final String jobCheckPolicies;

    private final String jobForceUpdate;

    private final String jobApiToken;

    private final String product;

    private final String productVersion;

    private final String projectToken;

    private final String libIncludes;

    private final String libExcludes;

    private final String mavenProjectToken;

    private final String requesterEmail;

    private final String moduleTokens;

    private final String modulesToInclude;

    private final String modulesToExclude;

    private final boolean ignorePomModules;

    /* --- Constructors --- */

    @DataBoundConstructor
    public WhiteSourcePublisher(String jobCheckPolicies,
                                String jobForceUpdate,
                                String jobApiToken,
                                String product,
                                String productVersion,
                                String projectToken,
                                String libIncludes,
                                String libExcludes,
                                String mavenProjectToken,
                                String requesterEmail,
                                String moduleTokens,
                                String modulesToInclude,
                                String modulesToExclude,
                                boolean ignorePomModules) {
        super();
        this.jobCheckPolicies = jobCheckPolicies;
        this.jobForceUpdate = jobForceUpdate;
        this.jobApiToken = jobApiToken;
        this.product = product;
        this.productVersion = productVersion;
        this.projectToken = projectToken;
        this.libIncludes = libIncludes;
        this.libExcludes = libExcludes;
        this.mavenProjectToken = mavenProjectToken;
        this.requesterEmail = requesterEmail;
        this.moduleTokens = moduleTokens;
        this.modulesToInclude = modulesToInclude;
        this.modulesToExclude = modulesToExclude;
        this.ignorePomModules = ignorePomModules;
    }

    /* --- Interface implementation methods --- */

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();

        logger.println("Updating White Source");

        Result buildResult = run.getResult();
        if (buildResult == null) {
            throw new RuntimeException("Failed to acquire build result");
        }
        if (buildResult.isWorseThan(Result.SUCCESS)) {
            logger.println("Build failed. Skipping update.");
            return;
        }

        if (WssUtils.isFreeStyleMaven(run.getParent())) {
            logger.println("Free style maven jobs are not supported in this version. See plugin documentation.");
            return;
        }

        DescriptorImpl globalConfig = (DescriptorImpl) getDescriptor();

        // make sure we have an organization token
        String apiToken = globalConfig.apiToken;
        if (StringUtils.isNotBlank(jobApiToken)) {
            apiToken = jobApiToken;
        }
        if (StringUtils.isBlank(apiToken)) {
            logger.println("No API token configured. Skipping update.");
            return;
        }

        // should we check policies ?
        boolean shouldCheckPolicies;
        boolean checkAllLibraries;
        if (StringUtils.isBlank(jobCheckPolicies) || GLOBAL.equals(jobCheckPolicies)) {
            String checkPolicies = globalConfig.checkPolicies;
            shouldCheckPolicies = ENABLE_NEW.equals(checkPolicies) || ENABLE_ALL.equals(checkPolicies);
            checkAllLibraries = ENABLE_ALL.equals(checkPolicies);
        } else {
            shouldCheckPolicies = ENABLE_NEW.equals(jobCheckPolicies) || ENABLE_ALL.equals(jobCheckPolicies);
            checkAllLibraries = ENABLE_ALL.equals(jobCheckPolicies);
        }

        boolean isForceUpdate;
        if (StringUtils.isBlank(jobForceUpdate) || GLOBAL.equals(jobForceUpdate)) {
            isForceUpdate = globalConfig.globalForceUpdate;
        } else {
            isForceUpdate = JOB_FORCE_UPDATE.equals(jobForceUpdate);
        }

        // collect OSS usage information
        logger.println("Collecting OSS usage information");
        Collection<AgentProjectInfo> projectInfos;
        String productNameOrToken = product;
        if (run instanceof MavenModuleSetBuild) {
            MavenOssInfoExtractor extractor = new MavenOssInfoExtractor(modulesToInclude,
                    modulesToExclude, (MavenModuleSetBuild) run, listener, mavenProjectToken, moduleTokens, ignorePomModules);
            projectInfos = extractor.extract();
            if (StringUtils.isBlank(product)) {
                productNameOrToken = extractor.getTopMostProjectName();
            }
        } else if ((run instanceof FreeStyleBuild)) {
            GenericOssInfoExtractor extractor = new GenericOssInfoExtractor(libIncludes,
                    libExcludes, run, listener, projectToken, workspace);
            projectInfos = extractor.extract();
        } else {
            stopBuild(run, listener, "Unrecognized build type " + run.getClass().getName());
            return;
        }

        // send to white source
        if (CollectionUtils.isEmpty(projectInfos)) {
            logger.println("No open source information found.");
        } else {
            WhitesourceService service = createServiceClient(globalConfig);
            try {
                if (shouldCheckPolicies) {
                    logger.println("Checking policies");
                    CheckPolicyComplianceResult result = service.checkPolicyCompliance(apiToken, productNameOrToken ,productVersion, projectInfos, checkAllLibraries);
                    policyCheckReport(result, run, listener);
                    boolean hasRejections = result.hasRejections();
                    if (hasRejections && !isForceUpdate) {
                        stopBuild(run, listener, "Open source rejected by organization policies.");
                    } else {
                        String message = hasRejections ? "Some dependencies violate open source policies, however all" +
                                " were force updated to organization inventory." :
                                "All dependencies conform with open source policies.";
                        logger.println(message);
                        sendUpdate(apiToken, requesterEmail, productNameOrToken, projectInfos, service, logger);
                        if (globalConfig.failOnError && hasRejections) {
                            stopBuild(build, listener, "White Source Publisher failure");
                        }
                    }
                } else {
                    sendUpdate(apiToken, requesterEmail, productNameOrToken, projectInfos, service, logger);
                }
            } catch (WssServiceException e) {
                stopBuildOnError(run, globalConfig.failOnError, listener, e);
            } catch (IOException e) {
                stopBuildOnError(run, globalConfig.failOnError, listener, e);
            } catch (RuntimeException e) {
                stopBuildOnError(run, globalConfig.failOnError, listener, e);
            } finally {
                service.shutdown();
            }
        }

        return;
    }

    /* --- Public methods --- */

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /* --- Private methods --- */

    private WhitesourceService createServiceClient(DescriptorImpl globalConfig) {
        String url = globalConfig.serviceUrl;
        if (StringUtils.isNotBlank(url)){
            if (!url.endsWith("/")) {
                url += "/";
            }
            url += "agent";
        }
        int connectionTimeout = DEFAULT_TIMEOUT;
        if (NumberUtils.isNumber(globalConfig.connectionTimeout)) {
            int connectionTimeoutInteger = Integer.parseInt(globalConfig.connectionTimeout);
            connectionTimeout = connectionTimeoutInteger > 0 ? connectionTimeoutInteger : connectionTimeout;
        }
        boolean proxyConfigured = isProxyConfigured(globalConfig);
        WhitesourceService service = new WhitesourceService(Constants.AGENT_TYPE, Constants.AGENT_VERSION, url,
                proxyConfigured, connectionTimeout);

        if (proxyConfigured) {
            String host, userName, password;
            int port;
            if (globalConfig.overrideProxySettings) {
                host = globalConfig.server;
                port = StringUtils.isBlank(globalConfig.port) ? 0 : Integer.parseInt(globalConfig.port);
                userName = globalConfig.userName;
                password = globalConfig.password;
            }
            else { // proxy is configured in jenkins global settings
                Hudson hudsonInstance = Hudson.getInstance();
                if (hudsonInstance == null) {
                    throw new RuntimeException("Failed to acquire Hudson Instance");
                }
                final ProxyConfiguration proxy = hudsonInstance.proxy;
                host = proxy.name;
                port = proxy.port;
                userName = proxy.getUserName();
                password = proxy.getPassword();
            }
            // ditch protocol if present
            try {
                URL tmpUrl = new URL(host);
                host = tmpUrl.getHost();
            } catch (MalformedURLException e) {
                // nothing to do here
            }
            service.getClient().setProxy(host, port, userName, password);
        }

        return service;
    }

    private boolean isProxyConfigured(DescriptorImpl globalConfig) {
        Hudson hudsonInstance = Hudson.getInstance();
        return globalConfig.overrideProxySettings ||
               (hudsonInstance != null && hudsonInstance.proxy != null);
    }

    private void policyCheckReport(CheckPolicyComplianceResult result, Run<?, ?> run, TaskListener listener) //CheckPoliciesResult
            throws IOException, InterruptedException {
        listener.getLogger().println("Generating policy check report");

        PolicyCheckReport report = new PolicyCheckReport(result,
                run.getParent().getName(),
                Integer.toString(run.getNumber()));
        report.generate(run.getRootDir(), false);

                run.addAction(new PolicyCheckReportAction(run));
    }

    private void sendUpdate(String orgToken,
                            String requesterEmail,
                            String productNameOrToken,
                            Collection<AgentProjectInfo> projectInfos,
                            WhitesourceService service,
                            PrintStream logger) throws WssServiceException {
        logger.println("Sending to White Source");
        UpdateInventoryResult updateResult = service.update(orgToken, requesterEmail, productNameOrToken, productVersion, projectInfos);
        logUpdateResult(updateResult, logger);
    }

    private void stopBuild(Run<?, ?> run, TaskListener listener, String message) {
        listener.error(message);
        run.setResult(Result.FAILURE);
    }

    private void stopBuildOnError(Run<?, ?> run, boolean failOnError, TaskListener listener, Exception e) {
        if (e instanceof IOException) {
            Util.displayIOException((IOException) e, listener);
        }
        e.printStackTrace(listener.fatalError("White Source Publisher failure"));
        if (failOnError) {
            run.setResult(Result.FAILURE);
        }
    }

    private void logUpdateResult(UpdateInventoryResult result, PrintStream logger) {
        logger.println("White Source update results: ");
        logger.println("White Source organization: " + result.getOrganization());
        logger.println(result.getCreatedProjects().size() + " Newly created projects:");
        logger.println(StringUtils.join(result.getCreatedProjects(), ","));
        logger.println(result.getUpdatedProjects().size() + " existing projects were updated:");
        logger.println(StringUtils.join(result.getUpdatedProjects(), ","));
    }

    /* --- Nested classes --- */

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        /* --- Members--- */

        private String serviceUrl;

        private String apiToken;

        private String checkPolicies;

        private boolean globalForceUpdate;

        private boolean failOnError;

        private boolean overrideProxySettings;

        private String server;

        private String port;

        private String userName;

        private String password;

        private String connectionTimeout;

        /* --- Constructors--- */

        /**
         * Default constructor
         */
        public DescriptorImpl() {
            super();
            load();
        }

        /* --- Overridden methods --- */

        @Override
        public String getDisplayName() {
            return "White Source Publisher";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/whitesource/help/help.html";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            apiToken = json.getString("apiToken");
            serviceUrl  = json.getString("serviceUrl");
            checkPolicies = json.getString("checkPolicies");
            failOnError = json.getBoolean("failOnError");
            globalForceUpdate = json.getBoolean("globalForceUpdate");

            JSONObject proxySettings = (JSONObject) json.get("proxySettings");
            if (proxySettings == null) {
                overrideProxySettings = false;
            }
            else {
                overrideProxySettings = true;
                server = proxySettings.getString("server");
                port = proxySettings.getString("port");
                userName = proxySettings.getString("userName");
                password = proxySettings.getString("password");
            }
            connectionTimeout =json.getString("connectionTimeout");
            save();

            return super.configure(req, json);
        }

        /* --- Public methods --- */

        public FormValidation doCheckApiToken(@QueryParameter String apiToken) {
            return FormValidation.validateRequired(apiToken);
        }

        public FormValidation doCheckConnectionTimeout(@QueryParameter String connectionTimeout) {
            FormValidation formValidation = FormValidation.validatePositiveInteger(connectionTimeout);
            return formValidation;
        }

        /* --- Getters / Setters --- */

        public String getServiceUrl() {
            return serviceUrl;
        }

        public void setServiceUrl(String serviceUrl) {
            this.serviceUrl = serviceUrl;
        }

        public String getApiToken() {
            return apiToken;
        }

        public void setApiToken(String apiToken) {
            this.apiToken = apiToken;
        }

        public String getCheckPolicies() {
            return checkPolicies;
        }

        public void setCheckPolicies(String checkPolicies) {
            this.checkPolicies = checkPolicies;
        }

        public boolean isFailOnError() { return failOnError; }

        public void setFailOnError(boolean failOnError) { this.failOnError = failOnError; }

        public boolean isOverrideProxySettings() {
            return overrideProxySettings;
        }

        public void setOverrideProxySettings(boolean overrideProxySettings) {
            this.overrideProxySettings = overrideProxySettings;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(String connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        public boolean isGlobalForceUpdate() {
            return globalForceUpdate;
        }

        public void setGlobalForceUpdate(boolean globalForceUpdate) {
            this.globalForceUpdate = globalForceUpdate;
        }
    }

    /* --- Getters --- */

    public String getJobCheckPolicies() {
        return jobCheckPolicies;
    }

    public String getJobForceUpdate() {
        return jobForceUpdate;
    }

    public String getJobApiToken() {
        return jobApiToken;
    }

    public String getProduct() {
        return product;
    }

    public String getProductVersion() {
        return productVersion;
    }

    public String getProjectToken() {
        return projectToken;
    }

    public String getLibIncludes() {
        return libIncludes;
    }

    public String getLibExcludes() {
        return libExcludes;
    }

    public String getMavenProjectToken() {
        return mavenProjectToken;
    }

    public String getRequesterEmail() {
        return requesterEmail;
    }

    public String getModuleTokens() {
        return moduleTokens;
    }

    public String getModulesToInclude() {
        return modulesToInclude;
    }

    public String getModulesToExclude() {
        return modulesToExclude;
    }

    public boolean isIgnorePomModules() {
        return ignorePomModules;
    }
}
