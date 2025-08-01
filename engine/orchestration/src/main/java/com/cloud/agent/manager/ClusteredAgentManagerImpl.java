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
package com.cloud.agent.manager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import com.cloud.resource.ResourceState;
import org.apache.cloudstack.ca.CAManager;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.extensions.command.ExtensionServerActionBaseCommand;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.ha.dao.HAConfigDao;
import org.apache.cloudstack.maintenance.ManagementServerMaintenanceManager;
import org.apache.cloudstack.maintenance.command.BaseShutdownManagementServerHostCommand;
import org.apache.cloudstack.maintenance.command.CancelMaintenanceManagementServerHostCommand;
import org.apache.cloudstack.maintenance.command.CancelShutdownManagementServerHostCommand;
import org.apache.cloudstack.maintenance.command.PrepareForMaintenanceManagementServerHostCommand;
import org.apache.cloudstack.maintenance.command.PrepareForShutdownManagementServerHostCommand;
import org.apache.cloudstack.maintenance.command.TriggerShutdownManagementServerHostCommand;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.managed.context.ManagedContextTimerTask;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.outofbandmanagement.dao.OutOfBandManagementDao;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.commons.collections.CollectionUtils;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CancelCommand;
import com.cloud.agent.api.ChangeAgentAnswer;
import com.cloud.agent.api.ChangeAgentCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.PropagateResourceEventCommand;
import com.cloud.agent.api.ScheduleHostScanTaskCommand;
import com.cloud.agent.api.TransferAgentCommand;
import com.cloud.agent.transport.Request;
import com.cloud.agent.transport.Request.Version;
import com.cloud.agent.transport.Response;
import com.cloud.cluster.ClusterManager;
import com.cloud.cluster.ClusterManagerListener;
import com.cloud.cluster.ClusterServicePdu;
import com.cloud.cluster.ClusteredAgentRebalanceService;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.agentlb.AgentLoadBalancerPlanner;
import com.cloud.cluster.agentlb.HostTransferMapVO;
import com.cloud.cluster.agentlb.HostTransferMapVO.HostTransferState;
import com.cloud.cluster.agentlb.dao.HostTransferMapDao;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.cluster.dao.ManagementServerHostPeerDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.UnsupportedVersionException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.Status.Event;
import com.cloud.resource.ServerResource;
import com.cloud.serializer.GsonHelper;
import com.cloud.utils.DateUtil;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.TaskExecutionException;
import com.cloud.utils.nio.Link;
import com.cloud.utils.nio.Task;
import com.google.gson.Gson;

public class ClusteredAgentManagerImpl extends AgentManagerImpl implements ClusterManagerListener, ClusteredAgentRebalanceService {
    private static ScheduledExecutorService s_transferExecutor = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Cluster-AgentRebalancingExecutor"));
    private final long rebalanceTimeOut = 300000; // 5 mins - after this time remove the agent from the transfer list

    public final static long STARTUP_DELAY = 5000;
    public final static long SCAN_INTERVAL = 90000; // 90 seconds, it takes 60 sec for xenserver to fail login
    public final static int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 5; // 5 seconds
    protected Set<Long> _agentToTransferIds = new HashSet<>();
    Gson _gson;
    protected HashMap<String, SocketChannel> _peers;
    protected HashMap<String, SSLEngine> _sslEngines;
    private final Timer _timer = new Timer("ClusteredAgentManager Timer");
    boolean _agentLbHappened = false;
    private int _mshostCounter = 0;

    @Inject
    protected ClusterManager _clusterMgr = null;
    @Inject
    protected ManagementServerHostDao _mshostDao;
    @Inject
    protected ManagementServerHostPeerDao _mshostPeerDao;
    @Inject
    protected HostTransferMapDao _hostTransferDao;
    @Inject
    protected List<AgentLoadBalancerPlanner> _lbPlanners;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    ConfigDepot _configDepot;
    @Inject
    private OutOfBandManagementDao outOfBandManagementDao;
    @Inject
    private HAConfigDao haConfigDao;
    @Inject
    private CAManager caService;
    @Inject
    private ManagementServerMaintenanceManager managementServerMaintenanceManager;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    ExtensionsManager extensionsManager;

    protected ClusteredAgentManagerImpl() {
        super();
    }

    protected final ConfigKey<Boolean> EnableLB = new ConfigKey<>(Boolean.class, "agent.lb.enabled", "Advanced", "false", "Enable direct agents load balancing between management server nodes", true);
    protected final ConfigKey<Double> ConnectedAgentThreshold = new ConfigKey<>(Double.class, "agent.load.threshold", "Advanced", "0.7",
            "What percentage of the direct agents can be held by one management server before load balancing happens", true, EnableLB.key());
    protected final ConfigKey<Integer> LoadSize = new ConfigKey<>(Integer.class, "direct.agent.load.size", "Advanced", "16", "How many direct agents to connect to in each round", true);
    protected final ConfigKey<Integer> ScanInterval = new ConfigKey<>(Integer.class, "direct.agent.scan.interval", "Advanced", "90", "Interval between scans to load direct agents", false,
            ConfigKey.Scope.Global, 1000);

    @Override
    public boolean configure(final String name, final Map<String, Object> xmlParams) throws ConfigurationException {
        _peers = new HashMap<>(7);
        _sslEngines = new HashMap<>(7);
        _nodeId = ManagementServerNode.getManagementServerId();

        logger.info("Configuring ClusterAgentManagerImpl. management server node id(msid): {}", _nodeId);

        ClusteredAgentAttache.initialize(this);

        _clusterMgr.registerListener(this);
        _clusterMgr.registerDispatcher(new ClusterDispatcher());

        _gson = GsonHelper.getGson();

        return super.configure(name, xmlParams);
    }

    @Override
    public boolean start() {
        if (!super.start()) {
            return false;
        }
        _timer.schedule(new DirectAgentScanTimerTask(), STARTUP_DELAY, ScanInterval.value());
        logger.debug("Scheduled direct agent scan task to run at an interval of {} seconds", ScanInterval.value());

        ManagementServerHostVO msHost = _mshostDao.findByMsid(_nodeId);
        if (msHost != null && (ManagementServerHost.State.Maintenance.equals(msHost.getState()) || ManagementServerHost.State.PreparingForMaintenance.equals(msHost.getState()))) {
            s_transferExecutor.shutdownNow();
            cleanupTransferMap(_nodeId);
            return true;
        }

        // Schedule tasks for agent rebalancing
        if (isAgentRebalanceEnabled()) {
            cleanupTransferMap(_nodeId);
            s_transferExecutor.scheduleAtFixedRate(getAgentRebalanceScanTask(), 60000, 60000, TimeUnit.MILLISECONDS);
            s_transferExecutor.scheduleAtFixedRate(getTransferScanTask(), 60000, ClusteredAgentRebalanceService.DEFAULT_TRANSFER_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
        }

        return true;
    }

    public void scheduleHostScanTask() {
        _timer.schedule(new DirectAgentScanTimerTask(), 0);
        logger.debug("Scheduled a direct agent scan task");
    }

    private void runDirectAgentScanTimerTask() {
        scanDirectAgentToLoad();
    }

    protected void scanDirectAgentToLoad() {
        logger.trace("Begin scanning directly connected hosts");

        // for agents that are self-managed, threshold to be considered as disconnected after pingtimeout
        final long cutSeconds = (System.currentTimeMillis() >> 10) - mgmtServiceConf.getTimeout();
        final List<HostVO> hosts = _hostDao.findAndUpdateDirectAgentToLoad(cutSeconds, LoadSize.value().longValue(), _nodeId);
        final List<HostVO> appliances = _hostDao.findAndUpdateApplianceToLoad(cutSeconds, _nodeId);

        if (hosts != null) {
            hosts.addAll(appliances);
            if (!hosts.isEmpty()) {
                logger.debug("Found {} unmanaged direct hosts, processing connect for them...", hosts.size());
                for (final HostVO host : hosts) {
                    try {
                        final AgentAttache agentattache = findAttache(host.getId());
                        if (agentattache != null) {
                            // already loaded, skip
                            if (agentattache.forForward()) {
                                logger.info("{} is detected down, but we have a forward attache running, disconnect this one before launching the host", host);
                                removeAgent(agentattache, Status.Disconnected);
                            } else {
                                logger.debug("Host {} status is {} but has an AgentAttache which is not forForward, try to load directly", host, host.getStatus());
                                Status hostStatus = investigate(agentattache);
                                if (Status.Up == hostStatus) {
                                    /* Got ping response from host, bring it back */
                                    logger.info("After investigation, Agent for host {} is determined to be up and running", host);
                                    agentStatusTransitTo(host, Event.Ping, _nodeId);
                                } else {
                                    logger.debug("After investigation, AgentAttache is not null but host status is {}, try to load directly {}", hostStatus, host);
                                    loadDirectlyConnectedHost(host, false);
                                }
                            }
                        } else {
                            logger.debug("AgentAttache is null, loading directly connected {}", host);
                            loadDirectlyConnectedHost(host, false);
                        }
                    } catch (final Throwable e) {
                        logger.warn(" can not load directly connected {} due to ", host, e);
                    }
                }
            }
        }
        logger.trace("End scanning directly connected hosts");
    }

    private class DirectAgentScanTimerTask extends ManagedContextTimerTask {
        @Override
        protected void runInContext() {
            try {
                runDirectAgentScanTimerTask();
            } catch (final Throwable e) {
                logger.error("Unexpected exception {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public Task create(final Task.Type type, final Link link, final byte[] data) {
        return new ClusteredAgentHandler(type, link, data);
    }

    protected AgentAttache createAttache(final HostVO host) {
        logger.debug("create forwarding ClusteredAgentAttache for {}", host);
        long id = host.getId();
        final AgentAttache attache = new ClusteredAgentAttache(this, id, host.getUuid(), host.getName(), host.getHypervisorType());
        AgentAttache old;
        synchronized (_agents) {
            old = _agents.get(host.getId());
            _agents.put(host.getId(), attache);
        }
        if (old != null) {
            logger.debug("Remove stale agent attache from current management server");
            removeAgent(old, Status.Removed);
        }
        return attache;
    }

    @Override
    protected AgentAttache createAttacheForConnect(final HostVO host, final Link link) {
        logger.debug("create ClusteredAgentAttache for {}",  host);
        final AgentAttache attache = new ClusteredAgentAttache(this, host.getId(), host.getUuid(), host.getName(), host.getHypervisorType(), link, host.isInMaintenanceStates());
        link.attach(attache);
        AgentAttache old;
        synchronized (_agents) {
            old = _agents.get(host.getId());
            _agents.put(host.getId(), attache);
        }
        if (old != null) {
            old.disconnect(Status.Removed);
        }
        return attache;
    }

    @Override
    protected AgentAttache createAttacheForDirectConnect(final Host host, final ServerResource resource) {
        logger.debug("Create ClusteredDirectAgentAttache for {}.", host);
        final DirectAgentAttache attache = new ClusteredDirectAgentAttache(this, host.getId(), host.getUuid(), host.getName(), host.getHypervisorType(), _nodeId, resource, host.isInMaintenanceStates());
        AgentAttache old;
        synchronized (_agents) {
            old = _agents.get(host.getId());
            _agents.put(host.getId(), attache);
        }
        if (old != null) {
            old.disconnect(Status.Removed);
        }
        return attache;
    }

    @Override
    protected boolean handleDisconnectWithoutInvestigation(final AgentAttache attache, final Status.Event event, final boolean transitState, final boolean removeAgent) {
        return handleDisconnect(attache, event, false, true, removeAgent);
    }

    @Override
    protected boolean handleDisconnectWithInvestigation(final AgentAttache attache, final Status.Event event) {
        return handleDisconnect(attache, event, true, true, true);
    }

    protected boolean handleDisconnect(final AgentAttache agent, final Status.Event event, final boolean investigate, final boolean broadcast, final boolean removeAgent) {
        boolean res;
        if (!investigate) {
            res = super.handleDisconnectWithoutInvestigation(agent, event, true, removeAgent);
        } else {
            res = super.handleDisconnectWithInvestigation(agent, event);
        }

        if (res) {
            if (broadcast) {
                notifyNodesInCluster(agent);
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean executeUserRequest(final long hostId, final Event event) throws AgentUnavailableException {
        if (event == Event.AgentDisconnected) {
            final AgentAttache attache = findAttache(hostId);
            logger.debug("Received agent disconnect event for host {} ({})",  hostId, attache);
            if (attache != null) {
                // don't process disconnect if the host is being rebalanced
                if (isAgentRebalanceEnabled()) {
                    final HostTransferMapVO transferVO = _hostTransferDao.findById(hostId);
                    if (transferVO != null) {
                        if (transferVO.getFutureOwner() == _nodeId && transferVO.getState() == HostTransferState.TransferStarted) {
                            logger.debug(
                                    "Not processing {} event for the host [id: {}, uuid: {}, name: {}] as the host is being connected to {}",
                                    Event.AgentDisconnected, hostId, attache.getUuid(), attache.getName(), _nodeId);
                            return true;
                        }
                    }
                }

                // don't process disconnect if the disconnect came for the host via delayed cluster notification,
                // but the host has already reconnected to the current management server
                if (!attache.forForward()) {
                    logger.debug(
                            "Not processing {} event for the host [id: {}, uuid: {}, name: {}] as the host is directly connected to the current management server {}",
                            Event.AgentDisconnected, hostId, attache.getUuid(), attache.getName(), _nodeId);
                    return true;
                }

                return super.handleDisconnectWithoutInvestigation(attache, Event.AgentDisconnected, false, true);
            }

            return true;
        } else {
            return super.executeUserRequest(hostId, event);
        }
    }

    @Override
    public void reconnect(final long hostId) throws CloudRuntimeException, AgentUnavailableException {
        Boolean result = propagateAgentEvent(hostId, Event.ShutdownRequested);
        if (result == null) {
            super.reconnect(hostId);
            return;
        }
        if (!result) {
                throw new CloudRuntimeException(String.format("Failed to propagate agent change request event: %s to host: %s", Event.ShutdownRequested, hostId));
        }
    }

    public void notifyNodesInCluster(final AgentAttache attache) {
        logger.debug("Notifying other nodes of to disconnect");
        final Command[] cmds = new Command[]{new ChangeAgentCommand(attache.getId(), Event.AgentDisconnected)};
        _clusterMgr.broadcast(attache.getId(), _gson.toJson(cmds));
    }

    // notifies MS peers to schedule a host scan task immediately, triggered during addHost operation
    public void notifyNodesInClusterToScheduleHostScanTask() {
        logger.debug("Notifying other MS nodes to run host scan task");
        final Command[] cmds = new Command[]{new ScheduleHostScanTaskCommand()};
        _clusterMgr.broadcast(0, _gson.toJson(cmds));
    }

    protected void logT(final byte[] bytes, final String msg) {
        logger.trace("Seq {}-{}: MgmtId {} : {}", Request.getAgentId(bytes), Request.getSequence(bytes),
                Request.getManagementServerId(bytes), (Request.isRequest(bytes) ? "Req: " : "Resp: ") + msg);
    }

    protected void logD(final byte[] bytes, final String msg) {
        logger.debug("Seq {}-{}: MgmtId {} : {}", Request.getAgentId(bytes), Request.getSequence(bytes),
                Request.getManagementServerId(bytes), (Request.isRequest(bytes) ? "Req: " : "Resp: ") + msg);
    }

    protected void logI(final byte[] bytes, final String msg) {
        logger.info("Seq {}-{}: MgmtId {} : {}", Request.getAgentId(bytes), Request.getSequence(bytes),
                Request.getManagementServerId(bytes), (Request.isRequest(bytes) ? "Req: " : "Resp: ") + msg);
    }

    public boolean routeToPeer(final String peer, final byte[] bytes) {
        int i = 0;
        SocketChannel ch = null;
        SSLEngine sslEngine;
        while (i++ < 5) {
            ch = connectToPeer(peer, ch);
            if (ch == null) {
                try {
                    logD(bytes, "Unable to establish connection to route to peer: " + Request.parse(bytes));
                } catch (ClassNotFoundException | UnsupportedVersionException e) {
                    // Request.parse thrown exception when we try to log it, log as much as we can
                    logD(bytes, "Unable to establish connection to route to peer, and Request.parse further caught exception" + e.getMessage());
                }
                return false;
            }
            sslEngine = getSSLEngine(peer);
            if (sslEngine == null) {
                logD(bytes, "Unable to get SSLEngine of peer: " + peer);
                return false;
            }
            try {
                logD(bytes, "Routing to peer");
                Link.write(ch, new ByteBuffer[]{ByteBuffer.wrap(bytes)}, sslEngine);
                return true;
            } catch (final IOException e) {
                try {
                    logI(bytes, "Unable to route to peer: " + Request.parse(bytes) + " due to " + e.getMessage());
                } catch (ClassNotFoundException | UnsupportedVersionException ex) {
                    // Request.parse thrown exception when we try to log it, log as much as we can
                    logI(bytes, "Unable to route to peer due to" + e.getMessage() + ". Also caught exception when parsing request: " + ex.getMessage());
                }
            }
        }
        return false;
    }

    public String findPeer(final long hostId) {
        return getPeerName(hostId);
    }

    public SSLEngine getSSLEngine(final String peerName) {
        return _sslEngines.get(peerName);
    }

    public void cancel(final String peerName, final long hostId, final long sequence, final String reason) {
        final CancelCommand cancel = new CancelCommand(sequence, reason);
        final Request req = new Request(hostId, _nodeId, cancel, true);
        req.setControl(true);
        routeToPeer(peerName, req.getBytes());
    }

    public void closePeer(final String peerName) {
        synchronized (_peers) {
            final SocketChannel ch = _peers.get(peerName);
            if (ch != null) {
                try {
                    ch.close();
                } catch (final IOException e) {
                    logger.warn("Unable to close peer socket connection to {}", peerName);
                }
            }
            _peers.remove(peerName);
            _sslEngines.remove(peerName);
        }
    }

    public SocketChannel connectToPeer(final String peerName, final SocketChannel prevCh) {
        synchronized (_peers) {
            final SocketChannel ch = _peers.get(peerName);
            SSLEngine sslEngine;
            if (prevCh != null) {
                try {
                    prevCh.close();
                } catch (final Exception e) {
                    logger.info("[ignored] failed to get close resource for previous channel Socket: {}", e.getLocalizedMessage());
                }
            }
            if (ch == null || ch == prevCh) {
                final ManagementServerHost ms = _clusterMgr.getPeer(peerName);
                if (ms == null) {
                    logger.info("Unable to find peer: {}",  peerName);
                    return null;
                }
                final String ip = ms.getServiceIP();
                InetAddress addr;
                int port = Port.value();
                try {
                    addr = InetAddress.getByName(ip);
                } catch (final UnknownHostException e) {
                    throw new CloudRuntimeException("Unable to resolve " + ip);
                }
                SocketChannel ch1 = null;
                try {
                    ch1 = SocketChannel.open(new InetSocketAddress(addr, port));
                    ch1.configureBlocking(false);
                    ch1.socket().setKeepAlive(true);
                    ch1.socket().setSoTimeout(60 * 1000);
                    try {
                        SSLContext sslContext = Link.initManagementSSLContext(caService);
                        sslEngine = sslContext.createSSLEngine(ip, port);
                        sslEngine.setUseClientMode(true);
                        sslEngine.setEnabledProtocols(SSLUtils.getSupportedProtocols(sslEngine.getEnabledProtocols()));
                        sslEngine.beginHandshake();
                        if (!Link.doHandshake(ch1, sslEngine)) {
                            ch1.close();
                            throw new IOException(String.format("SSL: Handshake failed with peer management server '%s' on %s:%d ", peerName, ip, port));
                        }
                        logger.info("SSL: Handshake done with peer management server '{}' on {}:{} ", peerName, ip, port);
                    } catch (final Exception e) {
                        ch1.close();
                        throw new IOException("SSL: Fail to init SSL! " + e);
                    }
                    logger.debug("Connection to peer opened: {}, ip: {}", peerName, ip);
                    _peers.put(peerName, ch1);
                    _sslEngines.put(peerName, sslEngine);
                    return ch1;
                } catch (final IOException e) {
                    if (ch1 != null) {
                        try {
                            ch1.close();
                        } catch (final IOException ex) {
                            logger.error("failed to close failed peer socket: {}",  ex);
                        }
                    }
                    logger.warn("Unable to connect to peer management server: {}, ip {} due to {}", peerName, ip, e.getMessage(), e);
                    return null;
                }
            }

            logger.trace("Found open channel for peer: {}",  peerName);
            return ch;
        }
    }

    public SocketChannel connectToPeer(final long hostId, final SocketChannel prevCh) {
        final String peerName = getPeerName(hostId);
        if (peerName == null) {
            return null;
        }

        return connectToPeer(peerName, prevCh);
    }

    @Override
    protected AgentAttache getAttache(final Long hostId) throws AgentUnavailableException {
        assert hostId != null : "Who didn't check their id value?";
        final HostVO host = _hostDao.findById(hostId);
        if (host == null) {
            throw new AgentUnavailableException("Can't find the host ", hostId);
        }

        AgentAttache agent = findAttache(hostId);
        if (agent == null || !agent.forForward()) {
            if (isHostOwnerSwitched(host)) {
                logger.debug("{} has switched to another management server, need to update agent map with a forwarding agent attache",  host);
                agent = createAttache(host);
            }
        }
        if (agent == null) {
            final AgentUnavailableException ex = new AgentUnavailableException("Host with specified id is not in the right state: " + host.getStatus(), hostId);
            ex.addProxyObject(host.getUuid());
            throw ex;
        }

        return agent;
    }

    @Override
    public boolean stop() {
        if (_peers != null) {
            for (final SocketChannel ch : _peers.values()) {
                try {
                    logger.info("Closing: {}",  ch.toString());
                    ch.close();
                } catch (final IOException e) {
                    logger.info("[ignored] error on closing channel: {}",  ch.toString(), e);
                }
            }
        }
        _timer.cancel();

        // cancel all transfer tasks
        s_transferExecutor.shutdownNow();
        cleanupTransferMap(_nodeId);

        return super.stop();
    }

    @Override
    public void startDirectlyConnectedHosts(final boolean forRebalance) {
        // override and let it be dummy for purpose, we will scan and load direct agents periodically.
        // We may also pickup agents that have been left over from other crashed management server
    }

    public class ClusteredAgentHandler extends AgentHandler {

        public ClusteredAgentHandler(final Task.Type type, final Link link, final byte[] data) {
            super(type, link, data);
        }

        @Override
        protected void doTask(final Task task) throws TaskExecutionException {
            try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
                if (task.getType() != Type.DATA) {
                    super.doTask(task);
                    return;
                }

                final byte[] data = task.getData();
                final Version ver = Request.getVersion(data);
                if (ver.ordinal() != Version.v1.ordinal() && ver.ordinal() != Version.v3.ordinal()) {
                    logger.warn("Wrong version for clustered agent request");
                    super.doTask(task);
                    return;
                }

                final long hostId = Request.getAgentId(data);
                final Link link = task.getLink();

                if (Request.fromServer(data)) {
                    final AgentAttache agent = findAttache(hostId);

                    if (Request.isControl(data)) {
                        if (agent == null) {
                            logD(data, "No attache to process cancellation");
                            return;
                        }
                        final Request req = Request.parse(data);
                        final Command[] cmds = req.getCommands();
                        final CancelCommand cancel = (CancelCommand) cmds[0];
                        logD(data, "Cancel request received");
                        agent.cancel(cancel.getSequence());
                        final Long current = agent._currentSequence;
                        // if the request is the current request, always have to trigger sending next request in
                        // sequence,
                        // otherwise the agent queue will be blocked
                        if (req.executeInSequence() && current != null && current == Request.getSequence(data)) {
                            agent.sendNext(Request.getSequence(data));
                        }
                        return;
                    }

                    try {
                        if (agent == null || agent.isClosed()) {
                            throw new AgentUnavailableException("Unable to route to agent ", hostId);
                        }

                        if (Request.isRequest(data) && Request.requiresSequentialExecution(data)) {
                            // route it to the agent.
                            // But we have the serialize the control commands here so we have
                            // to deserialize this and send it through the agent attache.
                            final Request req = Request.parse(data);
                            agent.send(req, null);
                        } else {
                            if (agent instanceof Routable) {
                                final Routable cluster = (Routable) agent;
                                cluster.routeToAgent(data);
                            } else {
                                agent.send(Request.parse(data));
                            }
                            return;
                        }
                    } catch (final AgentUnavailableException e) {
                        logD(data, e.getMessage());
                        cancel(Long.toString(Request.getManagementServerId(data)), hostId, Request.getSequence(data), e.getMessage());
                    }
                } else {
                    final long mgmtId = Request.getManagementServerId(data);
                    if (mgmtId != -1 && mgmtId != _nodeId) {
                        routeToPeer(Long.toString(mgmtId), data);
                        if (Request.requiresSequentialExecution(data)) {
                            final AgentAttache attache = (AgentAttache) link.attachment();
                            if (attache != null) {
                                attache.sendNext(Request.getSequence(data));
                            }
                            logD(data, "No attache to process " + Request.parse(data));
                        }
                    } else {
                        if (Request.isRequest(data)) {
                            super.doTask(task);
                        } else {
                            // received an answer.
                            final Response response = Response.parse(data);
                            final AgentAttache attache = findAttache(response.getAgentId());
                            if (attache == null) {
                                logger.info("SeqA {}-{} Unable to find attache to forward {}", response.getAgentId(), response.getSequence(), response.toString());
                                return;
                            }
                            if (!attache.processAnswers(response.getSequence(), response)) {
                                logger.info("SeqA {}-{}: Response is not processed: {}", attache.getId(), response.getSequence(), response.toString());
                            }
                        }
                    }
                }
            } catch (final ClassNotFoundException e) {
                final String message = String.format("ClassNotFoundException occurred when executing tasks! Error '%s'", e.getMessage());
                logger.error(message);
                throw new TaskExecutionException(message, e);
            } catch (final UnsupportedVersionException e) {
                final String message = String.format("UnsupportedVersionException occurred when executing tasks! Error '%s'", e.getMessage());
                logger.error(message);
                throw new TaskExecutionException(message, e);
            }
        }
    }

    @Override
    public void onManagementNodeJoined(final List<? extends ManagementServerHost> nodeList, final long selfNodeId) {
    }

    @Override
    public void onManagementNodeLeft(final List<? extends ManagementServerHost> nodeList, final long selfNodeId) {
        for (final ManagementServerHost vo : nodeList) {
            logger.info("Marking hosts as disconnected on Management server {}",  vo);
            final long lastPing = (System.currentTimeMillis() >> 10) - mgmtServiceConf.getTimeout();
            _hostDao.markHostsAsDisconnected(vo.getMsid(), lastPing);
            outOfBandManagementDao.expireServerOwnership(vo.getMsid());
            haConfigDao.expireServerOwnership(vo.getMsid());
            logger.info("Deleting entries from op_host_transfer table for Management server {}",  vo);
            cleanupTransferMap(vo.getMsid());
        }
    }

    @Override
    public void onManagementNodeIsolated() {
    }

    @Override
    public void removeAgent(final AgentAttache attache, final Status nextState) {
        if (attache == null) {
            return;
        }

        super.removeAgent(attache, nextState);
    }

    @Override
    public boolean executeRebalanceRequest(final long agentId, final long currentOwnerId, final long futureOwnerId, final Event event) throws AgentUnavailableException, OperationTimedoutException {
        return executeRebalanceRequest(agentId, currentOwnerId, futureOwnerId, event, false);
    }

    @Override
    public boolean executeRebalanceRequest(final long agentId, final long currentOwnerId, final long futureOwnerId, final Event event, boolean isConnectionTransfer) throws AgentUnavailableException, OperationTimedoutException {
        boolean result = false;
        if (event == Event.RequestAgentRebalance) {
            return setToWaitForRebalance(agentId);
        } else if (event == Event.StartAgentRebalance) {
            try {
                result = rebalanceHost(agentId, currentOwnerId, futureOwnerId, isConnectionTransfer);
            } catch (final Exception e) {
                logger.warn("Unable to rebalance host id={} ({})",  agentId, findAttache(agentId), e);
            }
        }
        return result;
    }

    @Override
    public void scheduleRebalanceAgents() {
        _timer.schedule(new AgentLoadBalancerTask(), 30000);
    }

    public class AgentLoadBalancerTask extends ManagedContextTimerTask {
        protected volatile boolean cancelled = false;

        public AgentLoadBalancerTask() {
            logger.debug("Agent load balancer task created");
        }

        @Override
        public synchronized boolean cancel() {
            if (!cancelled) {
                cancelled = true;
                logger.debug("Agent load balancer task cancelled");
                return super.cancel();
            }
            return true;
        }

        @Override
        protected synchronized void runInContext() {
            try {
                if (!cancelled) {
                    startRebalanceAgents();
                    logger.info("The agent load balancer task is now being cancelled");
                    cancelled = true;
                }
            } catch (final Throwable e) {
                logger.error("Unexpected exception {}",  e.toString(), e);
            }
        }
    }

    public void startRebalanceAgents() {
        logger.debug("Management server {} is asking other peers to rebalance their agents", _nodeId);
        final List<ManagementServerHostVO> allMS = _mshostDao.listBy(ManagementServerHost.State.Up);
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getManagementServerId(), Op.NNULL);
        sc.and(sc.entity().getType(), Op.EQ, Host.Type.Routing);
        final List<HostVO> allManagedAgents = sc.list();

        int avLoad;

        if (!allManagedAgents.isEmpty() && !allMS.isEmpty()) {
            avLoad = allManagedAgents.size() / allMS.size();
        } else {
            logger.debug("There are no hosts to rebalance in the system. Current number of active management server nodes in the system is {};" +
                    "number of managed agents is {}", allMS.size(), allManagedAgents.size());
            return;
        }

        if (avLoad == 0L) {
            logger.debug("As calculated average load is less than 1, rounding it to 1");
            avLoad = 1;
        }

        for (final ManagementServerHostVO node : allMS) {
            if (node.getMsid() != _nodeId) {

                List<HostVO> hostsToRebalance = new ArrayList<>();
                for (final AgentLoadBalancerPlanner lbPlanner : _lbPlanners) {
                    hostsToRebalance = lbPlanner.getHostsToRebalance(node, avLoad);
                    if (hostsToRebalance != null && !hostsToRebalance.isEmpty()) {
                        break;
                    }
                    logger.debug(
                            "Agent load balancer planner {} found no hosts to be rebalanced from management server {}",
                            lbPlanner.getName(), node);
                }

                if (hostsToRebalance != null && !hostsToRebalance.isEmpty()) {
                    logger.debug("Found {} hosts to rebalance from management server {}", hostsToRebalance.size(), node);
                    for (final HostVO host : hostsToRebalance) {
                        final long hostId = host.getId();
                        logger.debug("Asking management server {} to give away host id={}", node, host);
                        boolean result = true;

                        if (_hostTransferDao.findById(hostId) != null) {
                            logger.warn("Somebody else is already rebalancing host: {}", host);
                            continue;
                        }

                        HostTransferMapVO transfer = null;
                        try {
                            transfer = _hostTransferDao.startAgentTransfering(hostId, node.getMsid(), _nodeId);
                            final Answer[] answer = sendRebalanceCommand(node.getMsid(), hostId, node.getMsid(), _nodeId);
                            if (answer == null) {
                                logger.warn("Failed to get host {} from management server {}", host, node);
                                result = false;
                            }
                        } catch (final Exception ex) {
                            logger.warn("Failed to get host {} from management server {}", host, node, ex);
                            result = false;
                        } finally {
                            if (transfer != null) {
                                final HostTransferMapVO transferState = _hostTransferDao.findByIdAndFutureOwnerId(transfer.getId(), _nodeId);
                                if (!result && transferState != null && transferState.getState() == HostTransferState.TransferRequested) {
                                    logger.debug("Removing mapping from op_host_transfer as it failed to be set to transfer mode");
                                    // just remove the mapping (if exists) as nothing was done on the peer management
                                    // server yet
                                    _hostTransferDao.remove(transfer.getId());
                                }
                            }
                        }
                    }
                } else {
                    logger.debug("Found no hosts to rebalance from the management server {}",  node);
                }
            }
        }
    }

    private Answer[] sendRebalanceCommand(final long peer, final long agentId, final long currentOwnerId, final long futureOwnerId) {
        return sendRebalanceCommand(peer, agentId, currentOwnerId, futureOwnerId, Event.RequestAgentRebalance, false);
    }

    private Answer[] sendRebalanceCommand(final long peer, final long agentId, final long currentOwnerId, final long futureOwnerId, final Event event, final boolean isConnectionTransfer) {
        final TransferAgentCommand transfer = new TransferAgentCommand(agentId, currentOwnerId, futureOwnerId, event, isConnectionTransfer);
        final Commands commands = new Commands(Command.OnError.Stop);
        commands.addCommand(transfer);

        final Command[] cmds = commands.toCommands();

        try {
            logger.debug("Forwarding {} to {}", cmds[0].toString(), peer);
            final String peerName = Long.toString(peer);
            final String cmdStr = _gson.toJson(cmds);
            final String ansStr = _clusterMgr.execute(peerName, agentId, cmdStr, true);
            return _gson.fromJson(ansStr, Answer[].class);
        } catch (final Exception e) {
            logger.warn("Caught exception while talking to {}",  currentOwnerId, e);
            return null;
        }
    }

    public String getPeerName(final long agentHostId) {

        final HostVO host = _hostDao.findById(agentHostId);
        if (host != null && host.getManagementServerId() != null) {
            if (_clusterMgr.getSelfPeerName().equals(Long.toString(host.getManagementServerId()))) {
                return null;
            }

            return Long.toString(host.getManagementServerId());
        }
        return null;
    }

    public Boolean propagateAgentEvent(final long agentId, final Event event) throws AgentUnavailableException {
        final String msPeer = getPeerName(agentId);
        if (msPeer == null) {
            return null;
        }

        logger.debug("Propagating agent change request event: {} to agent: {} ({})", event.toString(), agentId, findAttache(agentId));
        final Command[] cmds = new Command[1];
        cmds[0] = new ChangeAgentCommand(agentId, event);

        final String ansStr = _clusterMgr.execute(msPeer, agentId, _gson.toJson(cmds), true);
        if (ansStr == null) {
            throw new AgentUnavailableException(agentId);
        }

        final Answer[] answers = _gson.fromJson(ansStr, Answer[].class);

        logger.debug("Result for agent change is {}",  answers[0].getResult());

        return answers[0].getResult();
    }

    private Runnable getTransferScanTask() {
        return new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                try {
                    logger.trace("Clustered agent transfer scan check, management server id: {}",  _nodeId);
                    synchronized (_agentToTransferIds) {
                        if (!_agentToTransferIds.isEmpty()) {
                            logger.debug("Found {} agents to transfer", _agentToTransferIds.size());
                            // for (Long hostId : _agentToTransferIds) {
                            for (final Iterator<Long> iterator = _agentToTransferIds.iterator(); iterator.hasNext(); ) {
                                final Long hostId = iterator.next();
                                final AgentAttache attache = findAttache(hostId);

                                // if the thread:
                                // 1) timed out waiting for the host to reconnect
                                // 2) recipient management server is not active any more
                                // 3) if the management server doesn't own the host any more
                                // remove the host from re-balance list and delete from op_host_transfer DB
                                // no need to do anything with the real attache as we haven't modified it yet
                                final Date cutTime = DateUtil.currentGMTTime();
                                final HostTransferMapVO transferMap = _hostTransferDao.findActiveHostTransferMapByHostId(hostId, new Date(cutTime.getTime() - rebalanceTimeOut));

                                if (transferMap == null) {
                                    logger.debug("Timed out waiting for the host id={} ({}) to be ready to transfer, skipping rebalance for the host", hostId, attache);
                                    iterator.remove();
                                    _hostTransferDao.completeAgentTransfer(hostId);
                                    continue;
                                }

                                if (transferMap.getInitialOwner() != _nodeId || attache == null || attache.forForward()) {
                                    logger.debug("Management server {} doesn't own host id={} ({}) any more, skipping rebalance for the host", _nodeId, hostId, attache);
                                    iterator.remove();
                                    _hostTransferDao.completeAgentTransfer(hostId);
                                    continue;
                                }

                                final ManagementServerHostVO ms = _mshostDao.findByMsid(transferMap.getFutureOwner());
                                if (ms != null && ms.getState() != ManagementServerHost.State.Up) {
                                    logger.debug("Can't transfer host {} ({}) as it's future owner is not in UP state: {}, skipping rebalance for the host", hostId, attache, ms);
                                    iterator.remove();
                                    _hostTransferDao.completeAgentTransfer(hostId);
                                    continue;
                                }

                                if (attache.getQueueSize() == 0 && attache.getNonRecurringListenersSize() == 0) {
                                    iterator.remove();
                                    try {
                                        _executor.execute(new RebalanceTask(hostId, transferMap.getInitialOwner(), transferMap.getFutureOwner()));
                                    } catch (final RejectedExecutionException ex) {
                                        logger.warn("Failed to submit rebalance task for host id={} ({}); postponing the execution", hostId, attache);
                                    }
                                } else {
                                    logger.debug("Agent {} ({}) can't be transferred yet as its request queue size is {} and listener queue size is {}",
                                            hostId, attache, attache.getQueueSize(), attache.getNonRecurringListenersSize());
                                }
                            }
                        } else {
                            logger.trace("Found no agents to be transferred by the management server {}",  _nodeId);
                        }
                    }
                } catch (final Throwable e) {
                    logger.error("Problem with the clustered agent transfer scan check!", e);
                }
            }
        };
    }

    private boolean setToWaitForRebalance(final long hostId) {
        logger.debug("Adding agent {} ({}) to the list of agents to transfer", hostId, findAttache(hostId));
        synchronized (_agentToTransferIds) {
            return _agentToTransferIds.add(hostId);
        }
    }

    protected boolean rebalanceHost(final long hostId, final long currentOwnerId, final long futureOwnerId) throws AgentUnavailableException {
        return rebalanceHost(hostId, currentOwnerId, futureOwnerId, false);
    }

    protected boolean rebalanceHost(final long hostId, final long currentOwnerId, final long futureOwnerId, final boolean isConnectionTransfer) throws AgentUnavailableException {
        boolean result = true;
        if (currentOwnerId == _nodeId) {
            if (!startRebalance(hostId)) {
                logger.debug("Failed to start agent rebalancing");
                finishRebalance(hostId, futureOwnerId, Event.RebalanceFailed);
                return false;
            }
            try {
                final Answer[] answer = sendRebalanceCommand(futureOwnerId, hostId, currentOwnerId, futureOwnerId, Event.StartAgentRebalance, isConnectionTransfer);
                if (answer == null || !answer[0].getResult()) {
                    result = false;
                }

            } catch (final Exception ex) {
                logger.warn("Host {} ({}) failed to connect to the management server {} as a part of rebalance process", hostId, findAttache(hostId), futureOwnerId, ex);
                result = false;
            }

            if (result) {
                logger.debug("Successfully transferred host id={} to management server {}", hostId, futureOwnerId);
                finishRebalance(hostId, futureOwnerId, Event.RebalanceCompleted);
            } else {
                logger.warn("Failed to transfer host id={} to management server {}", hostId, futureOwnerId);
                finishRebalance(hostId, futureOwnerId, Event.RebalanceFailed);
            }

        } else if (futureOwnerId == _nodeId) {
            final HostVO host = _hostDao.findById(hostId);
            try {
                logger.debug("Disconnecting {} as a part of rebalance process without notification", host);

                final AgentAttache attache = findAttache(hostId);
                if (attache != null) {
                    result = handleDisconnect(attache, Event.AgentDisconnected, false, false, true);
                }

                if (result) {
                    logger.debug("Loading directly connected host {} to the management server {} as a part of rebalance process", host, _nodeId);
                    result = loadDirectlyConnectedHost(host, true, isConnectionTransfer);
                } else {
                    logger.warn("Failed to disconnect {} as a part of rebalance process without notification", host);
                }

            } catch (final Exception ex) {
                logger.warn("Failed to load directly connected host {} to the management server {} a part of rebalance process without notification", host, _nodeId, ex);
                result = false;
            }

            if (result) {
                logger.debug("Successfully loaded directly connected {} to the management server {} a part of rebalance process without notification", host, _nodeId);
            } else {
                logger.warn("Failed to load directly connected {} to the management server {} a part of rebalance process without notification", host, _nodeId);
            }
        }

        return result;
    }

    protected void finishRebalance(final long hostId, final long futureOwnerId, final Event event) {

        final boolean success = event == Event.RebalanceCompleted;

        final AgentAttache attache = findAttache(hostId);
        logger.debug("Finishing rebalancing for the agent {} ({}) with event {}", hostId, attache, event);

        if (!(attache instanceof ClusteredAgentAttache)) {
            logger.debug("Unable to find forward attache for the host id={} assuming that the agent disconnected already", hostId);
            _hostTransferDao.completeAgentTransfer(hostId);
            return;
        }

        final ClusteredAgentAttache forwardAttache = (ClusteredAgentAttache) attache;

        if (success) {

            // 1) Set transfer mode to false - so the agent can start processing requests normally
            forwardAttache.setTransferMode(false);

            // 2) Get all transfer requests and route them to peer
            Request requestToTransfer = forwardAttache.getRequestToTransfer();
            while (requestToTransfer != null) {
                logger.debug("Forwarding request {} held in transfer attache [id: {}, uuid: {}, name: {}] from the management server {} to {}",
                        requestToTransfer.getSequence(), hostId, attache.getUuid(), attache.getName(), _nodeId, futureOwnerId);
                final boolean routeResult = routeToPeer(Long.toString(futureOwnerId), requestToTransfer.getBytes());
                if (!routeResult) {
                    logD(requestToTransfer.getBytes(), "Failed to route request to peer");
                }

                requestToTransfer = forwardAttache.getRequestToTransfer();
            }

            logger.debug("Management server {} completed agent [id: {}, uuid: {}, name: {}] rebalance to {}",
                    _nodeId, hostId, attache.getUuid(), attache.getName(), futureOwnerId);

        } else {
            failRebalance(hostId);
        }

        logger.debug("Management server {} completed agent [id: {}, uuid: {}, name: {}] rebalance", _nodeId, hostId, attache.getUuid(), attache.getName());
        _hostTransferDao.completeAgentTransfer(hostId);
    }

    protected void failRebalance(final long hostId) {
        AgentAttache attache = findAttache(hostId);
        try {
            logger.debug("Management server {} failed to rebalance agent {} ({})", _nodeId, hostId, attache);
            _hostTransferDao.completeAgentTransfer(hostId);
            handleDisconnectWithoutInvestigation(findAttache(hostId), Event.RebalanceFailed, true, true);
        } catch (final Exception ex) {
            logger.warn("Failed to reconnect host id={} ({}) as a part of failed rebalance task cleanup", hostId, attache);
        }
    }

    protected boolean startRebalance(final long hostId) {
        final HostVO host = _hostDao.findById(hostId);

        if (host == null || host.getRemoved() != null) {
            logger.warn("Unable to find host record, fail start rebalancing process");
            return false;
        }

        synchronized (_agents) {
            final ClusteredDirectAgentAttache attache = (ClusteredDirectAgentAttache) _agents.get(hostId);
            if (attache != null && attache.getQueueSize() == 0 && attache.getNonRecurringListenersSize() == 0) {
                handleDisconnectWithoutInvestigation(attache, Event.StartAgentRebalance, true, true);
                final ClusteredAgentAttache forwardAttache = (ClusteredAgentAttache) createAttache(host);
                if (forwardAttache == null) {
                    logger.warn("Unable to create a forward attache for the host {} as a part of rebalance process", host);
                    return false;
                }
                logger.debug("Putting agent {} to transfer mode", host);
                forwardAttache.setTransferMode(true);
                _agents.put(hostId, forwardAttache);
            } else {
                if (attache == null) {
                    logger.warn("Attache for the agent {} no longer exists on management server, can't start host rebalancing", host, _nodeId);
                } else {
                    logger.warn("Attache for the agent {} has request queue size {} and listener queue size {}, can't start host rebalancing",
                            host, attache.getQueueSize(), attache.getNonRecurringListenersSize());
                }
                return false;
            }
        }
        _hostTransferDao.startAgentTransfer(hostId);
        return true;
    }

    protected void cleanupTransferMap(final long msId) {
        final List<HostTransferMapVO> hostsJoingingCluster = _hostTransferDao.listHostsJoiningCluster(msId);

        for (final HostTransferMapVO hostJoingingCluster : hostsJoingingCluster) {
            _hostTransferDao.remove(hostJoingingCluster.getId());
        }

        final List<HostTransferMapVO> hostsLeavingCluster = _hostTransferDao.listHostsLeavingCluster(msId);
        for (final HostTransferMapVO hostLeavingCluster : hostsLeavingCluster) {
            _hostTransferDao.remove(hostLeavingCluster.getId());
        }
    }

    protected class RebalanceTask extends ManagedContextRunnable {
        Long hostId;
        Long currentOwnerId;
        Long futureOwnerId;

        public RebalanceTask(final long hostId, final long currentOwnerId, final long futureOwnerId) {
            this.hostId = hostId;
            this.currentOwnerId = currentOwnerId;
            this.futureOwnerId = futureOwnerId;
        }

        @Override
        protected void runInContext() {
            AgentAttache attache = findAttache(hostId);
            try {
                logger.debug("Rebalancing host id={} ({})", hostId, attache);
                rebalanceHost(hostId, currentOwnerId, futureOwnerId);
            } catch (final Exception e) {
                logger.warn("Unable to rebalance host id={} ({})", hostId, attache, e);
            }
        }
    }

    private String handleScheduleHostScanTaskCommand(final ScheduleHostScanTaskCommand cmd) {
        logger.debug("Intercepting resource manager command: {}", _gson.toJson(cmd));

        try {
            scheduleHostScanTask();
        } catch (final Exception e) {
            // Scheduling host scan task in peer MS is a best effort operation during host add, regular host scan
            // happens at fixed intervals anyways. So handling any exceptions that may be thrown
            logger.warn("Exception happened while trying to schedule host scan task on mgmt server {}, ignoring as regular host scan happens at fixed " +
                            "interval anyways", _clusterMgr.getSelfPeerName(), e);
            return null;
        }

        final Answer[] answers = new Answer[1];
        answers[0] = new Answer(cmd, true, null);
        return _gson.toJson(answers);
    }

    public Answer[] sendToAgent(final Long hostId, final Command[] cmds, final boolean stopOnError) throws AgentUnavailableException, OperationTimedoutException {
        final Commands commands = new Commands(stopOnError ? Command.OnError.Stop : Command.OnError.Continue);
        for (final Command cmd : cmds) {
            commands.addCommand(cmd);
        }
        return send(hostId, commands);
    }

    protected class ClusterDispatcher implements ClusterManager.Dispatcher {
        @Override
        public String getName() {
            return "ClusterDispatcher";
        }

        @Override
        public String dispatch(final ClusterServicePdu pdu) {

            logger.debug("Dispatch ->{}, json: {}", pdu.getAgentId(), pdu.getJsonPackage());

            Command[] cmds = null;
            try {
                cmds = _gson.fromJson(pdu.getJsonPackage(), Command[].class);
            } catch (final Throwable e) {
                assert false;
                logger.error("Exception in gson decoding : ", e);
            }

            if (cmds.length == 1 && cmds[0] instanceof ChangeAgentCommand) { // intercepted
                final ChangeAgentCommand cmd = (ChangeAgentCommand) cmds[0];

                logger.debug("Intercepting command for agent change: agent {} event: {}", cmd.getAgentId(), cmd.getEvent());
                boolean result;
                try {
                    result = executeAgentUserRequest(cmd.getAgentId(), cmd.getEvent());
                    logger.debug("Result is {}", result);

                } catch (final AgentUnavailableException e) {
                    logger.warn("Agent is unavailable", e);
                    return null;
                }

                final Answer[] answers = new Answer[1];
                answers[0] = new ChangeAgentAnswer(cmd, result);
                return _gson.toJson(answers);
            } else if (cmds.length == 1 && cmds[0] instanceof TransferAgentCommand) {
                final TransferAgentCommand cmd = (TransferAgentCommand) cmds[0];

                logger.debug("Intercepting command for agent rebalancing: agent: {}, event: {}, connection transfer: {}", cmd.getAgentId(), cmd.getEvent(), cmd.isConnectionTransfer());
                boolean result;
                try {
                    result = rebalanceAgent(cmd.getAgentId(), cmd.getEvent(), cmd.getCurrentOwner(), cmd.getFutureOwner(), cmd.isConnectionTransfer());
                    logger.debug("Result is {}", result);

                } catch (final AgentUnavailableException e) {
                    logger.warn("Agent is unavailable", e);
                    return null;
                } catch (final OperationTimedoutException e) {
                    logger.warn("Operation timed out", e);
                    return null;
                }
                final Answer[] answers = new Answer[1];
                answers[0] = new Answer(cmd, result, null);
                return _gson.toJson(answers);
            } else if (cmds.length == 1 && cmds[0] instanceof PropagateResourceEventCommand) {
                final PropagateResourceEventCommand cmd = (PropagateResourceEventCommand) cmds[0];

                logger.debug("Intercepting command to propagate event {} for host {} ({})", () -> cmd.getEvent().name(), cmd::getHostId, () -> _hostDao.findById(cmd.getHostId()));

                boolean result;
                try {
                    result = _resourceMgr.executeUserRequest(cmd.getHostId(), cmd.getEvent());
                    logger.debug("Result is {}", result);
                } catch (final AgentUnavailableException ex) {
                    logger.warn("Agent is unavailable", ex);
                    return null;
                }

                final Answer[] answers = new Answer[1];
                answers[0] = new Answer(cmd, result, null);
                return _gson.toJson(answers);
            } else if (cmds.length == 1 && cmds[0] instanceof ScheduleHostScanTaskCommand) {
                final ScheduleHostScanTaskCommand cmd = (ScheduleHostScanTaskCommand) cmds[0];
                return handleScheduleHostScanTaskCommand(cmd);
            } else if (cmds.length == 1 && cmds[0] instanceof BaseShutdownManagementServerHostCommand) {
                final BaseShutdownManagementServerHostCommand cmd = (BaseShutdownManagementServerHostCommand) cmds[0];
                return handleShutdownManagementServerHostCommand(cmd);
            } else if (cmds.length == 1 && cmds[0] instanceof ExtensionServerActionBaseCommand) {
                return extensionsManager.handleExtensionServerCommands((ExtensionServerActionBaseCommand)cmds[0]);
            }

            try {
                final long startTick = System.currentTimeMillis();
                logger.debug("Dispatch -> {}, json: {}", pdu.getAgentId(), pdu.getJsonPackage());

                final Answer[] answers = sendToAgent(pdu.getAgentId(), cmds, pdu.isStopOnError());
                if (answers != null) {
                    final String jsonReturn = _gson.toJson(answers);

                    logger.debug("Completed dispatching -> {}, json: {} in {} ms, return result: {}", pdu.getAgentId(),
                            pdu.getJsonPackage(), (System.currentTimeMillis() - startTick), jsonReturn);

                    return jsonReturn;
                } else {
                    logger.debug("Completed dispatching -> {}, json: {} in {} ms, return null result", pdu.getAgentId(),
                            pdu.getJsonPackage(), (System.currentTimeMillis() - startTick));
                }
            } catch (final AgentUnavailableException e) {
                logger.warn("Agent is unavailable", e);
            } catch (final OperationTimedoutException e) {
                logger.warn("Timed Out", e);
            }

            return null;
        }

        private String handleShutdownManagementServerHostCommand(BaseShutdownManagementServerHostCommand cmd) {
            if (cmd instanceof PrepareForMaintenanceManagementServerHostCommand) {
                logger.debug("Received PrepareForMaintenanceManagementServerHostCommand - preparing for maintenance");
                try {
                    managementServerMaintenanceManager.prepareForMaintenance(((PrepareForMaintenanceManagementServerHostCommand) cmd).getLbAlgorithm(), ((PrepareForMaintenanceManagementServerHostCommand) cmd).isForced());
                    return "Successfully prepared for maintenance";
                } catch(CloudRuntimeException e) {
                    return e.getMessage();
                }
            }
            if (cmd instanceof CancelMaintenanceManagementServerHostCommand) {
                logger.debug("Received CancelMaintenanceManagementServerHostCommand - cancelling maintenance");
                try {
                    managementServerMaintenanceManager.cancelMaintenance();
                    return "Successfully cancelled maintenance";
                } catch(CloudRuntimeException e) {
                    return e.getMessage();
                }
            }
            if (cmd instanceof PrepareForShutdownManagementServerHostCommand) {
                logger.debug("Received PrepareForShutdownManagementServerHostCommand - preparing to shut down");
                try {
                    managementServerMaintenanceManager.prepareForShutdown();
                    return "Successfully prepared for shutdown";
                } catch (CloudRuntimeException e) {
                    return e.getMessage();
                }
            }
            if (cmd instanceof TriggerShutdownManagementServerHostCommand) {
                logger.debug("Received TriggerShutdownManagementServerHostCommand - triggering a shut down");
                try {
                    managementServerMaintenanceManager.triggerShutdown();
                    return "Successfully triggered shutdown";
                } catch (CloudRuntimeException e) {
                    return e.getMessage();
                }
            }
            if (cmd instanceof CancelShutdownManagementServerHostCommand) {
                logger.debug("Received CancelShutdownManagementServerHostCommand - cancelling shut down");
                try {
                    managementServerMaintenanceManager.cancelShutdown();
                    return "Successfully cancelled shutdown";
                } catch (CloudRuntimeException e) {
                    return e.getMessage();
                }
            }
            throw new CloudRuntimeException("Unknown BaseShutdownManagementServerHostCommand command received : " + cmd);
        }
    }

    @Override
    public boolean transferDirectAgentsFromMS(String fromMsUuid, long fromMsId, long timeoutDurationInMs, boolean excludeHostsInMaintenance) {
        if (timeoutDurationInMs <= 0) {
            logger.debug("Not transferring direct agents from management server node {} (id: {}) to other nodes, invalid timeout duration", fromMsId, fromMsUuid);
            return false;
        }

        long transferStartTimeInMs = System.currentTimeMillis();
        if (CollectionUtils.isEmpty(getDirectAgentHosts(fromMsId, excludeHostsInMaintenance))) {
            logger.info("No direct agent hosts available on management server node {} (id: {}), to transfer", fromMsId, fromMsUuid);
            return true;
        }

        List<ManagementServerHostVO> msHosts = getUpMsHostsExcludingMs(fromMsId);
        if (msHosts.isEmpty()) {
            logger.warn("No management server nodes available to transfer agents from management server node {} (id: {})", fromMsId, fromMsUuid);
            return false;
        }

        logger.debug("Transferring direct agents from management server node {} (id: {}) to other nodes", fromMsId, fromMsUuid);
        int agentTransferFailedCount = 0;
        List<DataCenterVO> dataCenterList = dcDao.listAll();
        for (DataCenterVO dc : dataCenterList) {
            List<HostVO> directAgentHostsInDc = getDirectAgentHostsInDc(fromMsId, dc.getId(), excludeHostsInMaintenance);
            if (CollectionUtils.isEmpty(directAgentHostsInDc)) {
                continue;
            }
            logger.debug("Transferring {} direct agents from management server node {} (id: {}) of zone {}", directAgentHostsInDc.size(), fromMsId, fromMsUuid, dc);
            for (HostVO host : directAgentHostsInDc) {
                long transferElapsedTimeInMs = System.currentTimeMillis() - transferStartTimeInMs;
                if (transferElapsedTimeInMs >= timeoutDurationInMs) {
                    logger.debug("Stop transferring remaining direct agents from management server node {} (id: {}), timed out", fromMsId, fromMsUuid);
                    return false;
                }

                try {
                    if (_mshostCounter >= msHosts.size()) {
                        _mshostCounter = 0;
                    }
                    ManagementServerHostVO msHost = msHosts.get(_mshostCounter % msHosts.size());
                    _mshostCounter++;

                    _hostTransferDao.startAgentTransfering(host.getId(), fromMsId, msHost.getMsid());
                    if (!rebalanceAgent(host.getId(), Event.StartAgentRebalance, fromMsId, msHost.getMsid(), true)) {
                        agentTransferFailedCount++;
                    } else {
                        updateLastManagementServer(host.getId(), fromMsId);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to transfer direct agent of the host {} from management server node {} (id: {}), due to {}", host, fromMsId, fromMsUuid, e.getMessage());
                }
            }
        }

        return (agentTransferFailedCount == 0);
    }

    private List<HostVO> getDirectAgentHosts(long msId, boolean excludeHostsInMaintenance) {
        List<HostVO> directAgentHosts = new ArrayList<>();
        List<ResourceState> statesToExclude = excludeHostsInMaintenance ? ResourceState.s_maintenanceStates : List.of();
        List<HostVO> hosts = _hostDao.listHostsByMsResourceState(msId, statesToExclude);
        for (HostVO host : hosts) {
            AgentAttache agent = findAttache(host.getId());
            if (agent instanceof DirectAgentAttache) {
                directAgentHosts.add(host);
            }
        }

        return directAgentHosts;
    }

    private List<HostVO> getDirectAgentHostsInDc(long msId, long dcId, boolean excludeHostsInMaintenance) {
        List<HostVO> directAgentHosts = new ArrayList<>();
        // To exclude maintenance states use values from ResourceState as source of truth
        List<ResourceState> statesToExclude = excludeHostsInMaintenance ? ResourceState.s_maintenanceStates : List.of();
        List<HostVO> hosts = _hostDao.listHostsByMsDcResourceState(msId, dcId, statesToExclude);
        for (HostVO host : hosts) {
            AgentAttache agent = findAttache(host.getId());
            if (agent instanceof DirectAgentAttache) {
                directAgentHosts.add(host);
            }
        }

        return directAgentHosts;
    }

    private List<ManagementServerHostVO> getUpMsHostsExcludingMs(long avoidMsId) {
        final List<ManagementServerHostVO> msHosts = _mshostDao.listBy(ManagementServerHost.State.Up);
        msHosts.removeIf(ms -> ms.getMsid() == avoidMsId || _mshostPeerDao.findByPeerMsAndState(ms.getId(), ManagementServerHost.State.Up) == null);

        return msHosts;
    }

    private void updateLastManagementServer(long hostId, long msId) {
        HostVO hostVO = _hostDao.findById(hostId);
        if (hostVO != null) {
            hostVO.setLastManagementServerId(msId);
            _hostDao.update(hostId, hostVO);
        }
    }

    @Override
    public void onManagementServerPreparingForMaintenance() {
        logger.debug("Management server preparing for maintenance");
        super.onManagementServerPreparingForMaintenance();
    }

    @Override
    public void onManagementServerCancelPreparingForMaintenance() {
        logger.debug("Management server cancel preparing for maintenance");
        super.onManagementServerPreparingForMaintenance();

        // needed for the case when Management Server in Preparing For Maintenance but didn't go to Maintenance state
        // (where this variable will be reset)
        _agentLbHappened = false;
    }

    @Override
    public void onManagementServerMaintenance() {
        logger.debug("Management server maintenance enabled");
        s_transferExecutor.shutdownNow();
        cleanupTransferMap(_nodeId);
        _agentLbHappened = false;
        super.onManagementServerMaintenance();
    }

    @Override
    public void onManagementServerCancelMaintenance() {
        logger.debug("Management server maintenance disabled");
        super.onManagementServerCancelMaintenance();
        if (isAgentRebalanceEnabled()) {
            cleanupTransferMap(_nodeId);
            if (s_transferExecutor.isShutdown()) {
                s_transferExecutor = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Cluster-AgentRebalancingExecutor"));
                s_transferExecutor.scheduleAtFixedRate(getAgentRebalanceScanTask(), 60000, 60000, TimeUnit.MILLISECONDS);
                s_transferExecutor.scheduleAtFixedRate(getTransferScanTask(), 60000, ClusteredAgentRebalanceService.DEFAULT_TRANSFER_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
            }
        }
    }

    public boolean executeAgentUserRequest(final long agentId, final Event event) throws AgentUnavailableException {
        return executeUserRequest(agentId, event);
    }

    public boolean rebalanceAgent(final long agentId, final Event event, final long currentOwnerId, final long futureOwnerId) throws AgentUnavailableException, OperationTimedoutException {
        return executeRebalanceRequest(agentId, currentOwnerId, futureOwnerId, event);
    }

    public boolean rebalanceAgent(final long agentId, final Event event, final long currentOwnerId, final long futureOwnerId, boolean isConnectionTransfer) throws AgentUnavailableException, OperationTimedoutException {
        return executeRebalanceRequest(agentId, currentOwnerId, futureOwnerId, event, isConnectionTransfer);
    }

    public boolean isAgentRebalanceEnabled() {
        return EnableLB.value();
    }

    private Runnable getAgentRebalanceScanTask() {
        return new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                try {
                    logger.trace("Agent rebalance task check, management server id:{}", _nodeId);
                    // initiate agent lb task will be scheduled and executed only once, and only when number of agents
                    // loaded exceeds _connectedAgentsThreshold
                    if (!_agentLbHappened) {
                        QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
                        sc.and(sc.entity().getManagementServerId(), Op.NNULL);
                        sc.and(sc.entity().getType(), Op.EQ, Host.Type.Routing);
                        final List<HostVO> allManagedRoutingAgents = sc.list();

                        sc = QueryBuilder.create(HostVO.class);
                        sc.and(sc.entity().getType(), Op.EQ, Host.Type.Routing);
                        final List<HostVO> allAgents = sc.list();
                        final double allHostsCount = allAgents.size();
                        final double managedHostsCount = allManagedRoutingAgents.size();
                        if (allHostsCount > 0.0) {
                            final double load = managedHostsCount / allHostsCount;
                            if (load > ConnectedAgentThreshold.value()) {
                                logger.debug("Scheduling agent rebalancing task as the average agent load {} is more than the threshold {}", load, ConnectedAgentThreshold.value());
                                scheduleRebalanceAgents();
                                _agentLbHappened = true;
                            } else {
                                logger.debug("Not scheduling agent rebalancing task as the average load {} has not crossed the threshold", load, ConnectedAgentThreshold.value());
                            }
                        }
                    }
                } catch (final Throwable e) {
                    logger.error("Problem with the clustered agent transfer scan check!", e);
                }
            }
        };
    }

    @Override
    public void rescan() {
        // schedule a scan task immediately
        logger.debug("Scheduling a host scan task");
        // schedule host scan task on current MS
        scheduleHostScanTask();
        logger.debug("Notifying all peer MS to schedule host scan task");
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        final ConfigKey<?>[] keys = super.getConfigKeys();

        final List<ConfigKey<?>> keysLst = new ArrayList<>(Arrays.asList(keys));
        keysLst.add(EnableLB);
        keysLst.add(ConnectedAgentThreshold);
        keysLst.add(LoadSize);
        keysLst.add(ScanInterval);
        return keysLst.toArray(new ConfigKey<?>[keysLst.size()]);
    }
}
