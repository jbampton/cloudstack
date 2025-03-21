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
package com.cloud.ha;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;


import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PingTestCommand;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.router.VpcVirtualNetworkApplianceManager;
import com.cloud.vm.Nic;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

public class UserVmDomRInvestigator extends AbstractInvestigatorImpl {

    @Inject
    private final UserVmDao _userVmDao = null;
    @Inject
    private final AgentManager _agentMgr = null;
    @Inject
    private final NetworkModel _networkMgr = null;
    @Inject
    private final VpcVirtualNetworkApplianceManager _vnaMgr = null;

    @Override
    public boolean isVmAlive(VirtualMachine vm, Host host) throws UnknownVM {
        if (vm.getType() != VirtualMachine.Type.User) {
            if (logger.isDebugEnabled()) {
                logger.debug("Not a User Vm, unable to determine state of " + vm + " returning null");
            }
            throw new UnknownVM();
        }

        if (logger.isDebugEnabled()) {
            logger.debug("testing if " + vm + " is alive");
        }
        // to verify that the VM is alive, we ask the domR (router) to ping the VM (private IP)
        UserVmVO userVm = _userVmDao.findById(vm.getId());

        List<? extends Nic> nics = _networkMgr.getNicsForTraffic(userVm.getId(), TrafficType.Guest);

        for (Nic nic : nics) {
            if (nic.getIPv4Address() == null) {
                continue;
            }

            List<VirtualRouter> routers = _vnaMgr.getRoutersForNetwork(nic.getNetworkId());
            if (routers == null || routers.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Unable to find a router in network {} to ping {}",
                            _networkMgr.getNetwork(nic.getNetworkId()), vm);
                }
                continue;
            }

            Boolean result = null;
            for (VirtualRouter router : routers) {
                result = testUserVM(vm, nic, router);
                if (result != null) {
                    break;
                }
            }

            if (result == null) {
                continue;
            }

            return result;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Returning null since we're unable to determine state of " + vm);
        }
        throw new UnknownVM();
    }

    @Override
    public Status isAgentAlive(Host agent) {
        if (logger.isDebugEnabled()) {
            logger.debug("checking if agent ({}) is alive", agent);
        }

        if (agent.getPodId() == null) {
            return null;
        }

        List<HostVO> otherHosts = findHostByPod(agent.getPodId(), agent.getId());

        for (HostVO host : otherHosts) {
            if (logger.isDebugEnabled()) {
                logger.debug("sending ping from ({}) to agent's host ip address ({})", host, agent.getPrivateIpAddress());
            }
            Status hostState = testIpAddress(host.getId(), agent.getPrivateIpAddress());
            assert hostState != null;
            // In case of Status.Unknown, next host will be tried
            if (hostState == Status.Up) {
                if (logger.isDebugEnabled()) {
                    logger.debug("ping from ({}) to agent's host ip address ({}) successful, returning that agent is disconnected",
                            host, agent.getPrivateIpAddress());
                }
                return Status.Disconnected; // the computing host ip is ping-able, but the computing agent is down, report that the agent is disconnected
            } else if (hostState == Status.Down) {
                if (logger.isDebugEnabled()) {
                    logger.debug("returning host state: " + hostState);
                }
                return Status.Down;
            }
        }

        // could not reach agent, could not reach agent's host, unclear what the problem is but it'll require more investigation...
        if (logger.isDebugEnabled()) {
            logger.debug("could not reach agent, could not reach agent's host, returning that we don't have enough information");
        }
        return null;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    private Boolean testUserVM(VirtualMachine vm, Nic nic, VirtualRouter router) {
        String privateIp = nic.getIPv4Address();
        String routerPrivateIp = router.getPrivateIpAddress();

        List<Long> otherHosts = new ArrayList<Long>();
        if (vm.getHypervisorType() == HypervisorType.XenServer || vm.getHypervisorType() == HypervisorType.KVM) {
            otherHosts.add(router.getHostId());
        } else {
            List<HostVO> otherHostsList = findHostByPod(router.getPodIdToDeployIn(), null);
            otherHosts = otherHostsList.stream().map(HostVO::getId).collect(Collectors.toList());
        }
        for (Long hostId : otherHosts) {
            try {
                Answer pingTestAnswer = _agentMgr.easySend(hostId, new PingTestCommand(routerPrivateIp, privateIp));
                if (pingTestAnswer != null && pingTestAnswer.getResult()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("user vm's {} ip address {}  has been successfully " +
                                "pinged from the Virtual Router {}, returning that vm is alive",
                                vm, privateIp, router);
                    }
                    return Boolean.TRUE;
                }
            } catch (Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Couldn't reach due to", e);
                }
                continue;
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug(vm + " could not be pinged, returning that it is unknown");
        }
        return null;

    }
}
