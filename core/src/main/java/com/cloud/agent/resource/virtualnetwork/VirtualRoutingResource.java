//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.agent.resource.virtualnetwork;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.naming.ConfigurationException;

import com.cloud.agent.api.HandleCksIsoCommand;
import org.apache.cloudstack.agent.routing.ManageServiceCommand;
import com.cloud.agent.api.routing.UpdateNetworkCommand;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.network.router.VirtualRouter;
import com.cloud.utils.PasswordGenerator;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.ca.SetupCertificateAnswer;
import org.apache.cloudstack.ca.SetupCertificateCommand;
import org.apache.cloudstack.ca.SetupKeyStoreCommand;
import org.apache.cloudstack.ca.SetupKeystoreAnswer;
import org.apache.cloudstack.diagnostics.DeleteFileInVrCommand;
import org.apache.cloudstack.diagnostics.DiagnosticsAnswer;
import org.apache.cloudstack.diagnostics.DiagnosticsCommand;
import org.apache.cloudstack.diagnostics.PrepareFilesAnswer;
import org.apache.cloudstack.diagnostics.PrepareFilesCommand;
import org.apache.cloudstack.utils.security.KeyStoreUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.util.SubnetUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.joda.time.Duration;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckRouterAnswer;
import com.cloud.agent.api.CheckRouterCommand;
import com.cloud.agent.api.CheckS2SVpnConnectionsAnswer;
import com.cloud.agent.api.CheckS2SVpnConnectionsCommand;
import com.cloud.agent.api.GetDomRVersionAnswer;
import com.cloud.agent.api.GetDomRVersionCmd;
import com.cloud.agent.api.GetRouterAlertsAnswer;
import com.cloud.agent.api.routing.AggregationControlCommand;
import com.cloud.agent.api.routing.AggregationControlCommand.Action;
import com.cloud.agent.api.routing.GetRouterAlertsCommand;
import com.cloud.agent.api.routing.GetRouterMonitorResultsAnswer;
import com.cloud.agent.api.routing.GetRouterMonitorResultsCommand;
import com.cloud.agent.api.routing.GroupAnswer;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.resource.virtualnetwork.facade.AbstractConfigItemFacade;
import com.cloud.utils.ExecutionResult;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * VirtualNetworkResource controls and configures virtual networking
 *
 * @config
 * {@table
 *    || Param Name | Description | Values | Default ||
 *  }
 **/
public class VirtualRoutingResource {

    protected Logger logger = LogManager.getLogger(getClass());
    private VirtualRouterDeployer _vrDeployer;
    private Map<String, Queue<NetworkElementCommand>> _vrAggregateCommandsSet;
    protected Map<String, Lock> _vrLockMap = new HashMap<String, Lock>();

    private String _name;
    private int _sleep;
    private int _retry;
    private int _port;
    private Duration _eachTimeout;

    private String _cfgVersion = "1.0";

    public VirtualRoutingResource(VirtualRouterDeployer deployer) {
        _vrDeployer = deployer;
    }

    public Answer executeRequest(final NetworkElementCommand cmd) {
        boolean aggregated = false;
        String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        Lock lock;
        if (_vrLockMap.containsKey(routerName)) {
            lock = _vrLockMap.get(routerName);
        } else {
            lock = new ReentrantLock();
            _vrLockMap.put(routerName, lock);
        }
        lock.lock();

        try {
            ExecutionResult rc = _vrDeployer.prepareCommand(cmd);
            if (!rc.isSuccess()) {
                logger.error("Failed to prepare VR command due to " + rc.getDetails());
                return new Answer(cmd, false, rc.getDetails());
            }

            assert cmd.getRouterAccessIp() != null : "Why there is no access IP for VR?";

            if (cmd.isQuery()) {
                return executeQueryCommand(cmd);
            }

            if (cmd instanceof SetupKeyStoreCommand) {
                return execute((SetupKeyStoreCommand) cmd);
            }

            if (cmd instanceof SetupCertificateCommand) {
                return execute((SetupCertificateCommand) cmd);
            }

            if (cmd instanceof AggregationControlCommand) {
                return execute((AggregationControlCommand)cmd);
            }

            if (cmd instanceof UpdateNetworkCommand) {
                return execute((UpdateNetworkCommand) cmd);
            }

            if (cmd instanceof HandleCksIsoCommand) {
                return execute((HandleCksIsoCommand) cmd);
            }

            if (cmd instanceof ManageServiceCommand) {
                return execute((ManageServiceCommand) cmd);
            }

            if (_vrAggregateCommandsSet.containsKey(routerName)) {
                _vrAggregateCommandsSet.get(routerName).add(cmd);
                aggregated = true;
                // Clean up would be done after command has been executed
                //TODO: Deal with group answer as well
                return new Answer(cmd);
            }

            List<ConfigItem> cfg = generateCommandCfg(cmd);
            if (cfg == null) {
                return Answer.createUnsupportedCommandAnswer(cmd);
            }

            return applyConfig(cmd, cfg);
        } catch (final IllegalArgumentException e) {
            return new Answer(cmd, false, e.getMessage());
        } finally {
            lock.unlock();
            if (!aggregated) {
                ExecutionResult rc = _vrDeployer.cleanupCommand(cmd);
                if (!rc.isSuccess()) {
                    logger.error("Failed to cleanup VR command due to " + rc.getDetails());
                }
            }
        }
    }

    protected Answer execute(final HandleCksIsoCommand cmd) {
        String routerIp = getRouterSshControlIp(cmd);
        logger.info("Attempting to mount CKS ISO on Virtual Router");
        ExecutionResult result = _vrDeployer.executeInVR(routerIp, VRScripts.CKS_ISO_MOUNT_SERVE, String.valueOf(cmd.isMountCksIso()));
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    private Answer execute(final SetupKeyStoreCommand cmd) {
        final String args = String.format("/usr/local/cloud/systemvm/conf/agent.properties " +
                        "/usr/local/cloud/systemvm/conf/%s " +
                        "%s %d " +
                        "/usr/local/cloud/systemvm/conf/%s",
                KeyStoreUtils.KS_FILENAME,
                cmd.getKeystorePassword(),
                cmd.getValidityDays(),
                KeyStoreUtils.CSR_FILENAME);
        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), KeyStoreUtils.KS_SETUP_SCRIPT, args, Duration.standardMinutes(15));
        return new SetupKeystoreAnswer(result.getDetails());
    }

    private Answer execute(final SetupCertificateCommand cmd) {
        final String args = String.format("/usr/local/cloud/systemvm/conf/agent.properties %s " +
                        "/usr/local/cloud/systemvm/conf/%s %s " +
                        "/usr/local/cloud/systemvm/conf/%s \"%s\" " +
                        "/usr/local/cloud/systemvm/conf/%s \"%s\" " +
                        "/usr/local/cloud/systemvm/conf/%s \"%s\"",
                PasswordGenerator.generateRandomPassword(16),
                KeyStoreUtils.KS_FILENAME,
                KeyStoreUtils.SSH_MODE,
                KeyStoreUtils.CERT_FILENAME,
                cmd.getEncodedCertificate(),
                KeyStoreUtils.CACERT_FILENAME,
                cmd.getEncodedCaCertificates(),
                KeyStoreUtils.PKEY_FILENAME,
                cmd.getEncodedPrivateKey());
        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), KeyStoreUtils.KS_IMPORT_SCRIPT, args, Duration.standardMinutes(15));
        return new SetupCertificateAnswer(result.isSuccess());
    }

    private Answer executeQueryCommand(NetworkElementCommand cmd) {
        if (cmd instanceof CheckRouterCommand) {
            return execute((CheckRouterCommand)cmd);
        } else if (cmd instanceof GetDomRVersionCmd) {
            return execute((GetDomRVersionCmd)cmd);
        } else if (cmd instanceof CheckS2SVpnConnectionsCommand) {
            return execute((CheckS2SVpnConnectionsCommand)cmd);
        } else if (cmd instanceof GetRouterAlertsCommand) {
            return execute((GetRouterAlertsCommand)cmd);
        } else if (cmd instanceof DiagnosticsCommand) {
            return execute((DiagnosticsCommand) cmd);
        } else if (cmd instanceof PrepareFilesCommand) {
            return execute((PrepareFilesCommand) cmd);
        } else if (cmd instanceof DeleteFileInVrCommand) {
            return execute((DeleteFileInVrCommand)cmd);
        } else if (cmd instanceof GetRouterMonitorResultsCommand) {
            return execute((GetRouterMonitorResultsCommand)cmd);
        } else {
            logger.error("Unknown query command in VirtualRoutingResource!");
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
    }

    private String getRouterSshControlIp(NetworkElementCommand cmd) {
        String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        if (logger.isDebugEnabled())
            logger.debug("Use router's private IP for SSH control. IP : " + routerIp);
        return routerIp;
    }

    private Answer execute(UpdateNetworkCommand cmd) {
        IpAddressTO[] ipAddresses = cmd.getIpAddresses();
        String routerIp = getRouterSshControlIp(cmd);
        boolean finalResult = true;
        for (IpAddressTO ipAddressTO : ipAddresses) {
            try {
                SubnetUtils util = new SubnetUtils(ipAddressTO.getPublicIp(), ipAddressTO.getVlanNetmask());
                String address = util.getInfo().getCidrSignature();
                String subnet = address.split("/")[1];
                ExecutionResult result = _vrDeployer.executeInVR(routerIp, VRScripts.VR_UPDATE_INTERFACE_CONFIG,
                        ipAddressTO.getPublicIp() + " " + subnet + " " + ipAddressTO.getMtu() + " " + 15);
                if (logger.isDebugEnabled())
                    logger.debug("result: " + result.isSuccess() + ", output: " + result.getDetails());
                if (!Boolean.TRUE.equals(result.isSuccess())) {
                    if (result.getDetails().contains(String.format("Interface with IP %s not found", ipAddressTO.getPublicIp()))) {
                        logger.warn(String.format("Skipping IP: %s as it isn't configured on router interface", ipAddressTO.getPublicIp()));
                    } else if (ipAddressTO.getDetails().get(ApiConstants.REDUNDANT_STATE).equals(VirtualRouter.RedundantState.PRIMARY.name())) {
                        logger.warn(String.format("Failed to update interface mtu to %s on interface with ip: %s",
                                ipAddressTO.getMtu(), ipAddressTO.getPublicIp()));
                        finalResult = false;
                    }
                    continue;
                }
                logger.info(String.format("Successfully updated mtu to %s on interface with ip: %s",
                        ipAddressTO.getMtu(), ipAddressTO.getPublicIp()));
                finalResult &= true;
            } catch (Exception e) {
                String msg = "Prepare UpdateNetwork failed due to " + e.toString();
                logger.error(msg, e);
                return new Answer(cmd, e);
            }
        }
        if (finalResult) {
            return new Answer(cmd, true, null);
        }
        return new Answer(cmd, new CloudRuntimeException("Failed to update interface mtu"));
    }

    private Answer execute(ManageServiceCommand cmd) {
        String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        String args = cmd.getAction() + " " + cmd.getServiceName();
        ExecutionResult result = _vrDeployer.executeInVR(routerIp, VRScripts.MANAGE_SERVICE, args);
        if (result.isSuccess()) {
            return new Answer(cmd, true,
                    String.format("Successfully executed action: %s on service: %s. Details: %s",
                            cmd.getAction(), cmd.getServiceName(), result.getDetails()));
        } else {
            return new Answer(cmd, false, String.format("Failed to execute action: %s on service: %s. Details: %s",
                    cmd.getAction(), cmd.getServiceName(), result.getDetails()));
        }
    }

    private ExecutionResult applyConfigToVR(String routerAccessIp, ConfigItem c) {
        return applyConfigToVR(routerAccessIp, c, VRScripts.VR_SCRIPT_EXEC_TIMEOUT);
    }

    private ExecutionResult applyConfigToVR(String routerAccessIp, ConfigItem c, Duration timeout) {
        if (c instanceof FileConfigItem) {
            FileConfigItem configItem = (FileConfigItem)c;
            return _vrDeployer.createFileInVR(routerAccessIp, configItem.getFilePath(), configItem.getFileName(), configItem.getFileContents());
        } else if (c instanceof ScriptConfigItem) {
            ScriptConfigItem configItem = (ScriptConfigItem)c;
            return _vrDeployer.executeInVR(routerAccessIp, configItem.getScript(), configItem.getArgs(), timeout);
        }
        throw new CloudRuntimeException("Unable to apply unknown configitem of type " + c.getClass().getSimpleName());
    }

    private Answer applyConfig(NetworkElementCommand cmd, List<ConfigItem> cfg) {
        if (cfg.isEmpty()) {
            return new Answer(cmd, true, "Nothing to do");
        }

        List<ExecutionResult> results = new ArrayList<ExecutionResult>();
        List<String> details = new ArrayList<String>();
        boolean finalResult = false;
        for (ConfigItem configItem : cfg) {
            long startTimestamp = System.currentTimeMillis();
            ExecutionResult result = applyConfigToVR(cmd.getRouterAccessIp(), configItem, VRScripts.VR_SCRIPT_EXEC_TIMEOUT);
            if (logger.isDebugEnabled()) {
                long elapsed = System.currentTimeMillis() - startTimestamp;
                logger.debug("Processing " + configItem + " took " + elapsed + "ms");
            }
            if (result == null) {
                result = new ExecutionResult(false, "null execution result");
            }
            results.add(result);
            details.add(configItem.getInfo() + (result.isSuccess() ? " - success: " : " - failed: ") + result.getDetails());
            finalResult = result.isSuccess();
        }

        // Not sure why this matters, but log it anyway
        if (cmd.getAnswersCount() != results.size()) {
            logger.warn("Expected " + cmd.getAnswersCount() + " answers while executing " + cmd.getClass().getSimpleName() + " but received " + results.size());
        }

        if (results.size() == 1) {
            return new Answer(cmd, finalResult, results.get(0).getDetails());
        } else {
            return new GroupAnswer(cmd, finalResult, results.size(), details.toArray(new String[details.size()]));
        }
    }

    private CheckS2SVpnConnectionsAnswer execute(CheckS2SVpnConnectionsCommand cmd) {

        StringBuffer buff = new StringBuffer();
        for (String ip : cmd.getVpnIps()) {
            buff.append(ip);
            buff.append(" ");
        }
        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.S2SVPN_CHECK, buff.toString());
        return new CheckS2SVpnConnectionsAnswer(cmd, result.isSuccess(), result.getDetails());
    }

    private List<String> getFailingChecks(String line) {
        List<String> failingChecks = new ArrayList<>();
        for (String w : line.split(",")) {
            if (!w.trim().isEmpty()) {
                failingChecks.add(w.trim());
            }
        }
        return failingChecks;
    }

    private GetRouterMonitorResultsAnswer parseLinesForHealthChecks(GetRouterMonitorResultsCommand cmd, String executionResult) {
        List<String> failingChecks = new ArrayList<>();
        StringBuilder monitorResults = new StringBuilder();
        String[] lines = executionResult.trim().split("\n");
        boolean readingFailedChecks = false, readingMonitorResults = false;
        for (String line : lines) {
            line = line.trim();
            if (line.contains("FAILING CHECKS")) { // Toggle to reading failing checks from next line
                readingFailedChecks = true;
                readingMonitorResults = false;
            } else if (line.contains("MONITOR RESULTS")) { // Toggle to reading monitor results from next line
                readingFailedChecks = false;
                readingMonitorResults = true;
            } else if (readingFailedChecks && !readingMonitorResults) { // Reading failing checks section
                failingChecks.addAll(getFailingChecks(line));
            } else if (!readingFailedChecks && readingMonitorResults) { // Reading monitor checks result
                monitorResults.append(line);
            } else {
                logger.error("Unexpected lines reached while parsing health check response. Skipping line:- " + line);
            }
        }

        return new GetRouterMonitorResultsAnswer(cmd, true, failingChecks, monitorResults.toString());
    }

    private GetRouterMonitorResultsAnswer execute(GetRouterMonitorResultsCommand cmd) {
        String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        Pair<Boolean, String> fileSystemTestResult = checkRouterFileSystem(routerIp);
        if (!fileSystemTestResult.first()) {
            return new GetRouterMonitorResultsAnswer(cmd, false, null, fileSystemTestResult.second());
        }

        if (cmd.shouldValidateBasicTestsOnly()) {
            // Basic tests (connectivity and file system checks) are already validated
            return new GetRouterMonitorResultsAnswer(cmd, true, null, "success");
        }

        String args = cmd.shouldPerformFreshChecks() ? "true" : "false";
        logger.info("Fetching health check result for " + routerIp + " and executing fresh checks: " + args);
        ExecutionResult result = _vrDeployer.executeInVR(routerIp, VRScripts.ROUTER_MONITOR_RESULTS, args);

        if (!result.isSuccess()) {
            logger.warn("Result of " + cmd + " failed with details: " + result.getDetails());
            return new GetRouterMonitorResultsAnswer(cmd, false, null, result.getDetails());
        }

        if (result.getDetails().isEmpty()) {
            logger.warn("Result of " + cmd + " received no details.");
            return new GetRouterMonitorResultsAnswer(cmd, false, null, "No results available.");
        }

        return parseLinesForHealthChecks(cmd, result.getDetails());
    }

    private Pair<Boolean, String> checkRouterFileSystem(String routerIp) {
        ExecutionResult fileSystemWritableTestResult = _vrDeployer.executeInVR(routerIp, VRScripts.ROUTER_FILESYSTEM_WRITABLE_CHECK, null);
        if (fileSystemWritableTestResult.isSuccess()) {
            logger.debug("Router connectivity and file system writable check passed");
            return new Pair<Boolean, String>(true, "success");
        }

        String resultDetails = fileSystemWritableTestResult.getDetails();
        logger.warn("File system writable check failed with details: " + resultDetails);
        if (StringUtils.isNotBlank(resultDetails)) {
            final String readOnlyFileSystemError = "Read-only file system";
            if (resultDetails.contains(readOnlyFileSystemError)) {
                resultDetails = "Read-only file system";
            }
        } else {
            resultDetails = "No results available";
        }

        return new Pair<Boolean, String>(false, resultDetails);
    }

    private GetRouterAlertsAnswer execute(GetRouterAlertsCommand cmd) {

        String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        String args = cmd.getPreviousAlertTimeStamp();

        ExecutionResult result = _vrDeployer.executeInVR(routerIp, VRScripts.ROUTER_ALERTS, args);
        String alerts[] = null;
        String lastAlertTimestamp = null;

        if (result.isSuccess()) {
            if (!result.getDetails().isEmpty() && !result.getDetails().trim().equals("No Alerts")) {
                alerts = result.getDetails().trim().split("\\\\n");
                String[] lastAlert = alerts[alerts.length - 1].split(",");
                lastAlertTimestamp = lastAlert[0];
            }
            return new GetRouterAlertsAnswer(cmd, alerts, lastAlertTimestamp);
        } else {
            return new GetRouterAlertsAnswer(cmd, result.getDetails());
        }
    }

    private Answer execute(CheckRouterCommand cmd) {
        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.RVR_CHECK, null);
        if (!result.isSuccess()) {
            return new CheckRouterAnswer(cmd, result.getDetails());
        }
        return new CheckRouterAnswer(cmd, result.getDetails(), true);
    }

    private Answer execute(DiagnosticsCommand cmd) {
        _eachTimeout = Duration.standardSeconds(NumbersUtil.parseInt("60", 60));
        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.DIAGNOSTICS, cmd.getSrciptArguments(), _eachTimeout);
        if (!result.isSuccess()) {
            return new DiagnosticsAnswer(cmd, false, result.getDetails());
        }
        return new DiagnosticsAnswer(cmd, result.isSuccess(), result.getDetails());
    }

    private Answer execute(PrepareFilesCommand cmd) {
        String fileList = String.join(" ", cmd.getFilesToRetrieveList());
        _eachTimeout = Duration.standardSeconds(cmd.getTimeout());
        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.RETRIEVE_DIAGNOSTICS, fileList, _eachTimeout);
        if (result.isSuccess()) {
            return new PrepareFilesAnswer(cmd, true, result.getDetails());
        }
        return new PrepareFilesAnswer(cmd, false, result.getDetails());
    }

    private Answer execute(DeleteFileInVrCommand cmd) {
        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VR_FILE_CLEANUP, cmd.getFileName());
        if (result.isSuccess()) {
            return new Answer(cmd, result.isSuccess(), result.getDetails());
        }
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    private Answer execute(GetDomRVersionCmd cmd) {
        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VERSION, null);
        if (!result.isSuccess()) {
            return new GetDomRVersionAnswer(cmd, "GetDomRVersionCmd failed");
        }
        String[] lines = result.getDetails().split("&");
        if (lines.length != 2) {
            return new GetDomRVersionAnswer(cmd, result.getDetails());
        }
        return new GetDomRVersionAnswer(cmd, result.getDetails(), lines[0], lines[1]);
    }

    public boolean configureHostParams(final Map<String, String> params) {
        if (params.get("router.aggregation.command.each.timeout") != null) {
            String value = (String)params.get("router.aggregation.command.each.timeout");
            _eachTimeout = Duration.standardSeconds(NumbersUtil.parseLong(value, 600));
            if (logger.isDebugEnabled()){
                logger.debug("The router.aggregation.command.each.timeout in seconds is set to " + _eachTimeout.getStandardSeconds());
            }
        }

        return true;
    }

    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _name = name;

        String value = (String)params.get("ssh.sleep");
        _sleep = NumbersUtil.parseInt(value, 10) * 1000;

        value = (String)params.get("ssh.retry");
        _retry = NumbersUtil.parseInt(value, 36);

        value = (String)params.get("ssh.port");
        _port = NumbersUtil.parseInt(value, 3922);

        value = (String)params.get("router.aggregation.command.each.timeout");
        _eachTimeout = Duration.standardSeconds(NumbersUtil.parseInt(value, (int)VRScripts.VR_SCRIPT_EXEC_TIMEOUT.getStandardSeconds()));
        if (logger.isDebugEnabled()){
            logger.debug("The router.aggregation.command.each.timeout in seconds is set to " + _eachTimeout.getStandardSeconds());
        }

        if (_vrDeployer == null) {
            throw new ConfigurationException("Unable to find the resource for VirtualRouterDeployer!");
        }

        _vrAggregateCommandsSet = new HashMap<>();
        return true;
    }

    public boolean connect(final String ipAddress) {
        return connect(ipAddress, _port);
    }

    public boolean connect(final String ipAddress, final int port) {
        return connect(ipAddress, port, _sleep);
    }

    public boolean connect(final String ipAddress, int retry, int sleep) {
        for (int i = 0; i <= retry; i++) {
            SocketChannel sch = null;
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("Trying to connect to " + ipAddress);
                }
                sch = SocketChannel.open();
                sch.configureBlocking(true);

                final InetSocketAddress addr = new InetSocketAddress(ipAddress, _port);
                sch.connect(addr);
                return true;
            } catch (final IOException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Could not connect to " + ipAddress);
                }
            } finally {
                if (sch != null) {
                    try {
                        sch.close();
                    } catch (final IOException e) {
                    }
                }
            }
            try {
                Thread.sleep(sleep);
            } catch (final InterruptedException e) {
            }
        }

        logger.debug("Unable to logon to " + ipAddress);

        return false;
    }

    private List<ConfigItem> generateCommandCfg(NetworkElementCommand cmd) {
        /*
         * [TODO] Still have to migrate LoadBalancerConfigCommand and BumpUpPriorityCommand
         * [FIXME] Have a look at SetSourceNatConfigItem
         */
        logger.debug("Transforming " + cmd.getClass().getCanonicalName() + " to ConfigItems");

        final AbstractConfigItemFacade configItemFacade = AbstractConfigItemFacade.getInstance(cmd.getClass());

        return configItemFacade.generateConfig(cmd);
    }

    private Answer execute(AggregationControlCommand cmd) {
        Action action = cmd.getAction();
        String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        assert routerName != null;
        assert cmd.getRouterAccessIp() != null;

        if (action == Action.Start) {
            assert (!_vrAggregateCommandsSet.containsKey(routerName));

            Queue<NetworkElementCommand> queue = new LinkedBlockingQueue<>();
            _vrAggregateCommandsSet.put(routerName, queue);
            return new Answer(cmd, true, "Command aggregation started");
        } else if (action == Action.Finish) {
            Queue<NetworkElementCommand> queue = _vrAggregateCommandsSet.get(routerName);
            int answerCounts = 0;
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("#Apache CloudStack Virtual Router Config File\n");
                sb.append("<version>\n" + _cfgVersion + "\n</version>\n");
                for (NetworkElementCommand command : queue) {
                    answerCounts += command.getAnswersCount();
                    List<ConfigItem> cfg = generateCommandCfg(command);
                    if (cfg == null) {
                        logger.warn("Unknown commands for VirtualRoutingResource, but continue: " + cmd.toString());
                        continue;
                    }

                    for (ConfigItem c : cfg) {
                        sb.append(c.getAggregateCommand());
                    }
                }

                // TODO replace with applyConfig with a stop on fail
                String cfgFileName = "VR-"+ UUID.randomUUID().toString() + ".cfg";
                FileConfigItem fileConfigItem = new FileConfigItem(VRScripts.CONFIG_CACHE_LOCATION, cfgFileName, sb.toString());
                ScriptConfigItem scriptConfigItem = new ScriptConfigItem(VRScripts.VR_CFG, "-c " + VRScripts.CONFIG_CACHE_LOCATION + cfgFileName);
                // 120s is the minimal timeout
                Duration timeout = _eachTimeout.withDurationAdded(_eachTimeout.getStandardSeconds(), answerCounts);
                if (logger.isDebugEnabled()){
                    logger.debug("Aggregate action timeout in seconds is " + timeout.getStandardSeconds());
                }

                ExecutionResult result = applyConfigToVR(cmd.getRouterAccessIp(), fileConfigItem, timeout);
                if (!result.isSuccess()) {
                    return new Answer(cmd, false, result.getDetails());
                }

                result = applyConfigToVR(cmd.getRouterAccessIp(), scriptConfigItem, timeout);
                if (!result.isSuccess()) {
                    return new Answer(cmd, false, result.getDetails());
                }

                return new Answer(cmd, true, "Command aggregation finished");
            } finally {
                queue.clear();
                _vrAggregateCommandsSet.remove(routerName);
            }
        }
        return new Answer(cmd, false, "Fail to recognize aggregation action " + action.toString());
    }

    public boolean isSystemVMSetup(String vmName, String controlIp) throws InterruptedException {
        if (vmName.startsWith("s-") || vmName.startsWith("v-")) {
            ScriptConfigItem scriptConfigItem = new ScriptConfigItem(VRScripts.SYSTEM_VM_PATCHED, "/opt/cloud/bin/keystore*");
            ExecutionResult result = new ExecutionResult(false, "");
            int retries = 0;
            while (!result.isSuccess() && retries < 600) {
                result = applyConfigToVR(controlIp, scriptConfigItem, VRScripts.VR_SCRIPT_EXEC_TIMEOUT);
                if (result.isSuccess()) {
                    return true;
                }

                retries++;
                Thread.sleep(1000);
            }
            return false;
        }
        return true;
    }
}
