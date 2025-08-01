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
package com.cloud.deploy;

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.affinity.AffinityGroupDomainMapVO;
import org.apache.cloudstack.affinity.AffinityGroupProcessor;
import org.apache.cloudstack.affinity.AffinityGroupService;
import org.apache.cloudstack.affinity.AffinityGroupVMMapVO;
import org.apache.cloudstack.affinity.AffinityGroupVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupDomainMapDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.engine.cloud.entity.api.db.VMReservationVO;
import org.apache.cloudstack.engine.cloud.entity.api.db.dao.VMReservationDao;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.MessageSubscriber;
import org.apache.cloudstack.managed.context.ManagedContextTimerTask;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.manager.allocator.HostAllocator;
import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.Pod;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanner.PlannerResourceUsage;
import com.cloud.deploy.dao.PlannerHostReservationDao;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.gpu.GPU;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.org.Grouping;
import com.cloud.resource.ResourceManager;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.DateUtil;
import com.cloud.utils.LogUtils;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Manager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

public class DeploymentPlanningManagerImpl extends ManagerBase implements DeploymentPlanningManager, Manager, Listener,
StateListener<State, VirtualMachine.Event, VirtualMachine>, Configurable {

    @Inject
    AgentManager _agentMgr;
    @Inject
    private AccountDao accountDao;
    @Inject
    protected UserVmDao _vmDao;
    @Inject
    protected VMInstanceDao _vmInstanceDao;
    @Inject
    protected AffinityGroupDao _affinityGroupDao;
    @Inject
    protected AffinityGroupVMMapDao _affinityGroupVMMapDao;
    @Inject
    protected AffinityGroupDomainMapDao _affinityGroupDomainMapDao;
    @Inject
    AffinityGroupService _affinityGroupService;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    PlannerHostReservationDao _plannerHostReserveDao;
    private int _vmCapacityReleaseInterval;
    @Inject
    MessageBus _messageBus;
    private Timer _timer = null;
    private long _hostReservationReleasePeriod = 60L * 60L * 1000L; // one hour by default
    @Inject
    protected VMReservationDao _reservationDao;
    @Inject
    HostDetailsDao _hostDetailsDao;
    @Inject
    private VMTemplateDao templateDao;

    private static final long ADMIN_ACCOUNT_ROLE_ID = 1l;
    private static final long INITIAL_RESERVATION_RELEASE_CHECKER_DELAY = 30L * 1000L; // thirty seconds expressed in milliseconds
    protected long _nodeId = -1;
    protected static final List<HypervisorType> DEPLOYMENT_PLANNING_SKIP_HYPERVISORS = Arrays.asList(
            HypervisorType.BareMetal, HypervisorType.External);

    protected List<StoragePoolAllocator> _storagePoolAllocators;

    public List<StoragePoolAllocator> getStoragePoolAllocators() {
        return _storagePoolAllocators;
    }

    public void setStoragePoolAllocators(List<StoragePoolAllocator> storagePoolAllocators) {
        _storagePoolAllocators = storagePoolAllocators;
    }

    protected List<HostAllocator> _hostAllocators;

    public List<HostAllocator> getHostAllocators() {
        return _hostAllocators;
    }

    public void setHostAllocators(List<HostAllocator> hostAllocators) {
        _hostAllocators = hostAllocators;
    }

    @Inject
    protected HostDao _hostDao;
    @Inject
    protected HostPodDao _podDao;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    protected DedicatedResourceDao _dedicatedDao;
    @Inject
    protected GuestOSDao _guestOSDao = null;
    @Inject
    protected GuestOSCategoryDao _guestOSCategoryDao = null;
    @Inject
    protected DiskOfferingDao _diskOfferingDao;
    @Inject
    protected StoragePoolHostDao _poolHostDao;

    @Inject
    protected VolumeDao _volsDao;
    @Inject
    protected CapacityManager _capacityMgr;
    @Inject
    protected ConfigurationDao _configDao;
    @Inject
    protected PrimaryDataStoreDao _storagePoolDao;
    @Inject
    protected CapacityDao _capacityDao;
    @Inject
    protected AccountManager _accountMgr;
    @Inject
    protected StorageManager _storageMgr;
    @Inject
    DataStoreManager dataStoreMgr;
    @Inject
    protected ClusterDetailsDao _clusterDetailsDao;
    @Inject
    protected ResourceManager _resourceMgr;
    @Inject
    protected ServiceOfferingDetailsDao _serviceOfferingDetailsDao;

    protected List<DeploymentPlanner> _planners;

    public List<DeploymentPlanner> getPlanners() {
        return _planners;
    }

    public void setPlanners(List<DeploymentPlanner> planners) {
        _planners = planners;
    }

    protected List<AffinityGroupProcessor> _affinityProcessors;

    public List<AffinityGroupProcessor> getAffinityGroupProcessors() {
        return _affinityProcessors;
    }

    public void setAffinityGroupProcessors(List<AffinityGroupProcessor> affinityProcessors) {
        _affinityProcessors = affinityProcessors;
    }

    private static final List<CPU.CPUArch> clusterArchTypes = Arrays.asList(CPU.CPUArch.amd64, CPU.CPUArch.arm64);

    protected void avoidOtherClustersForDeploymentIfMigrationDisabled(VirtualMachine vm, Host lastHost, ExcludeList avoids) {
        if (lastHost == null || lastHost.getClusterId() == null ||
                ConfigurationManagerImpl.MIGRATE_VM_ACROSS_CLUSTERS.valueIn(vm.getDataCenterId())) {
            return;
        }
        List<VolumeVO> volumes = _volsDao.findUsableVolumesForInstance(vm.getId());
        if (CollectionUtils.isEmpty(volumes)) {
            return;
        }
        boolean storageMigrationNeededDuringClusterMigration = false;
        for (Volume volume : volumes) {
            StoragePoolVO pool = _storagePoolDao.findById(volume.getPoolId());
            if (pool != null && List.of(ScopeType.HOST, ScopeType.CLUSTER).contains(pool.getScope())) {
                storageMigrationNeededDuringClusterMigration = true;
                break;
            }
        }
        if (!storageMigrationNeededDuringClusterMigration) {
            return;
        }
        final Long lastHostClusterId = lastHost.getClusterId();
        logger.warn(String.format("VM last host ID: %d belongs to zone ID: %s for which config - %s is false and storage migration would be needed for inter-cluster migration, therefore, adding all other clusters except ID: %d from this zone to avoid list",
                lastHost.getId(), vm.getDataCenterId(), ConfigurationManagerImpl.MIGRATE_VM_ACROSS_CLUSTERS.key(), lastHostClusterId));
        List<Long> clusterIds = _clusterDao.listAllClusterIds(lastHost.getDataCenterId());
        Set<Long> existingAvoidedClusters = avoids.getClustersToAvoid();
        clusterIds = clusterIds.stream().filter(x -> !Objects.equals(x, lastHostClusterId) && (existingAvoidedClusters == null || !existingAvoidedClusters.contains(x))).collect(Collectors.toList());
        avoids.addClusterList(clusterIds);
    }

    @Override
    public DeployDestination planDeployment(VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoids, DeploymentPlanner planner)
            throws InsufficientServerCapacityException, AffinityConflictException {
        logger.debug(logDeploymentWithoutException(vmProfile.getVirtualMachine(), plan, avoids, planner));

        ServiceOffering offering = vmProfile.getServiceOffering();
        int cpuRequested = offering.getCpu() * offering.getSpeed();
        long ramRequested = offering.getRamSize() * 1024L * 1024L;
        VirtualMachine vm = vmProfile.getVirtualMachine();
        DataCenter dc = _dcDao.findById(vm.getDataCenterId());
        boolean volumesRequireEncryption = anyVolumeRequiresEncryption(_volsDao.findByInstance(vm.getId()));

        if (vm.getType() == VirtualMachine.Type.User || vm.getType() == VirtualMachine.Type.DomainRouter) {
            logger.debug("Checking non dedicated resources to deploy VM [{}].", vm);
            checkForNonDedicatedResources(vmProfile, dc, avoids);
        }

        logger.debug("Trying to allocate a host and storage pools from datacenter [{}], " +
                "pod [{}], cluster [{}], to deploy VM [{}] with requested CPU [{}] and requested RAM [{}].",
                dc::toString, () -> _podDao.findById(plan.getPodId()), () -> _clusterDao.findById(plan.getClusterId()),
                vm::toString, () -> cpuRequested, () -> toHumanReadableSize(ramRequested));

        logger.debug("ROOT volume [{}] {} to deploy VM [{}].",
                getRootVolume(_volsDao.findByInstance(vm.getId())),
                plan.getPoolId() != null ? "is ready" : "is not ready", vm);

        avoidDisabledResources(vmProfile, dc, avoids);
        avoidDifferentArchResources(vmProfile, dc, avoids);

        String haVmTag = (String)vmProfile.getParameter(VirtualMachineProfile.Param.HaTag);
        String uefiFlag = (String)vmProfile.getParameter(VirtualMachineProfile.Param.UefiFlag);

        if (plan.getHostId() != null && haVmTag == null) {
            return deployInSpecifiedHostWithoutHA(vmProfile, plan, avoids, planner, vm, dc, uefiFlag);
        }

        // call affinitygroup chain
        long vmGroupCount = _affinityGroupVMMapDao.countAffinityGroupsForVm(vm.getId());

        if (vmGroupCount > 0) {
            for (AffinityGroupProcessor processor : _affinityProcessors) {
                processor.process(vmProfile, plan, avoids);
            }
        }
        logger.debug("DeploymentPlan [{}] has not specified host. Trying to find another destination to deploy VM [{}], avoiding pods [{}], clusters [{}] and hosts [{}].",
                plan.getClass().getSimpleName(), vmProfile, StringUtils.join(avoids.getPodsToAvoid(), ", "),
                StringUtils.join(avoids.getClustersToAvoid(), ", "),
                StringUtils.join(avoids.getHostsToAvoid(), ", "));


        logger.debug("Deploy avoids pods: {}, clusters: {}, hosts: {}.", avoids.getPodsToAvoid(), avoids.getClustersToAvoid(),  avoids.getHostsToAvoid());
        logger.debug("Deploy hosts with priorities {}, hosts have NORMAL priority by default", plan.getHostPriorities());


        // call planners
        // DataCenter dc = _dcDao.findById(vm.getDataCenterId());
        // check if datacenter is in avoid set
        if (avoids.shouldAvoid(dc)) {
            if (logger.isDebugEnabled()) {
                logger.debug("DataCenter = '" + dc + "' provided is in avoid set, DeploymentPlanner cannot allocate the VM, returning.");
            }
            return null;
        }

        if (planner == null) {
            String plannerName = offering.getDeploymentPlanner();
            if (plannerName == null) {
                if (vm.getHypervisorType() == HypervisorType.BareMetal) {
                    plannerName = "BareMetalPlanner";
                } else if (vm.getHypervisorType() == HypervisorType.External) {
                    plannerName = "ExternalServerPlanner";
                } else {
                    plannerName = _configDao.getValue(Config.VmDeploymentPlanner.key());
                }
            }
            planner = getDeploymentPlannerByName(plannerName);
        }

        Host lastHost = null;

        String considerLastHostStr = (String)vmProfile.getParameter(VirtualMachineProfile.Param.ConsiderLastHost);
        boolean considerLastHost = vm.getLastHostId() != null && haVmTag == null &&
                (considerLastHostStr == null || Boolean.TRUE.toString().equalsIgnoreCase(considerLastHostStr));
        if (considerLastHost) {
            HostVO host = _hostDao.findById(vm.getLastHostId());
            logger.debug("This VM has last host_id specified, trying to choose the same host: " + host);
            lastHost = host;

            DeployDestination deployDestination = deployInVmLastHost(vmProfile, plan, avoids, planner, vm, dc, offering, cpuRequested, ramRequested, volumesRequireEncryption);
            if (deployDestination != null) {
                return deployDestination;
            }
        }

        avoidOtherClustersForDeploymentIfMigrationDisabled(vm, lastHost, avoids);

        DeployDestination dest = null;
        List<Long> clusterList = null;

        if (planner != null && planner.canHandle(vmProfile, plan, avoids)) {
            while (true) {
                if (planner instanceof DeploymentClusterPlanner) {

                    ExcludeList plannerAvoidInput =
                            new ExcludeList(avoids.getDataCentersToAvoid(), avoids.getPodsToAvoid(), avoids.getClustersToAvoid(), avoids.getHostsToAvoid(),
                                    avoids.getPoolsToAvoid());

                    clusterList = ((DeploymentClusterPlanner)planner).orderClusters(vmProfile, plan, avoids);

                    if (clusterList != null && !clusterList.isEmpty()) {
                        // planner refactoring. call allocators to list hosts
                        ExcludeList plannerAvoidOutput =
                                new ExcludeList(avoids.getDataCentersToAvoid(), avoids.getPodsToAvoid(), avoids.getClustersToAvoid(), avoids.getHostsToAvoid(),
                                        avoids.getPoolsToAvoid());

                        resetAvoidSet(plannerAvoidOutput, plannerAvoidInput);

                        dest = checkClustersforDestination(clusterList, vmProfile, plan, avoids, dc, getPlannerUsage(planner, vmProfile, plan, avoids), plannerAvoidOutput);
                        if (dest != null) {
                            return dest;
                        }
                        // reset the avoid input to the planners
                        resetAvoidSet(avoids, plannerAvoidOutput);
                    } else {
                        return null;
                    }
                } else {
                    dest = planner.plan(vmProfile, plan, avoids);
                    if (dest != null) {
                        long hostId = dest.getHost().getId();
                        avoids.addHost(dest.getHost().getId());

                        if (volumesRequireEncryption && !Boolean.parseBoolean(_hostDetailsDao.findDetail(hostId, Host.HOST_VOLUME_ENCRYPTION).getValue())) {
                            logger.warn("VM's volumes require encryption support, and the planner-provided host {} can't handle it", dest.getHost());
                            continue;
                        } else {
                            logger.debug("VM's volume encryption requirements are met by host {}", dest.getHost());
                        }

                        if (checkIfHostFitsPlannerUsage(dest.getHost(), DeploymentPlanner.PlannerResourceUsage.Shared)) {
                            // found destination
                            return dest;
                        } else {
                            // find another host - seems some concurrent
                            // deployment picked it up for dedicated access
                            continue;
                        }
                    } else {
                        return null;
                    }
                }
            }
        }
        return dest;
    }

    private void avoidDifferentArchResources(VirtualMachineProfile vmProfile, DataCenter dc, ExcludeList avoids) {
        VirtualMachineTemplate template = vmProfile.getTemplate();
        for (CPU.CPUArch arch : clusterArchTypes) {
            if (arch.equals(template.getArch())) {
                continue;
            }
            List<ClusterVO> avoidClusters = _clusterDao.listClustersByArchAndZoneId(dc.getId(), arch);
            if (CollectionUtils.isNotEmpty(avoidClusters)) {
                logger.debug("Excluding {} clusters as they are {} arch, conflicting with the requested arch {}",
                        avoidClusters.size(), arch.getType(), template.getArch().getType());
                List<Long> clusterIds = avoidClusters.stream().map(x -> x.getId()).collect(Collectors.toList());
                avoids.addClusterList(clusterIds);
            }
        }
    }

    private DeployDestination deployInVmLastHost(VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoids,
            DeploymentPlanner planner, VirtualMachine vm, DataCenter dc, ServiceOffering offering, int cpuRequested, long ramRequested,
            boolean volumesRequireEncryption) throws InsufficientServerCapacityException {
        HostVO host = _hostDao.findById(vm.getLastHostId());
        if (canUseLastHost(host, avoids, plan, vm, offering, volumesRequireEncryption)) {
            _hostDao.loadHostTags(host);
            _hostDao.loadDetails(host);
            if (host.getStatus() != Status.Up) {
                logger.debug("Cannot deploy VM [{}] to the last host [{}] because this host is not in UP state or is not enabled. Host current status [{}] and resource status [{}].",
                        vm, host, host.getState().name(), host.getResourceState());
                return null;
            }
            if (checkVmProfileAndHost(vmProfile, host)) {
                long cluster_id = host.getClusterId();
                ClusterDetailsVO cluster_detail_cpu = _clusterDetailsDao.findDetail(cluster_id, "cpuOvercommitRatio");
                ClusterDetailsVO cluster_detail_ram = _clusterDetailsDao.findDetail(cluster_id, "memoryOvercommitRatio");
                float cpuOvercommitRatio = Float.parseFloat(cluster_detail_cpu.getValue());
                float memoryOvercommitRatio = Float.parseFloat(cluster_detail_ram.getValue());

                boolean hostHasCpuCapability, hostHasCapacity = false;
                hostHasCpuCapability = _capacityMgr.checkIfHostHasCpuCapability(host, offering.getCpu(), offering.getSpeed());

                if (hostHasCpuCapability) {
                    // first check from reserved capacity
                    hostHasCapacity = _capacityMgr.checkIfHostHasCapacity(host, cpuRequested, ramRequested, true, cpuOvercommitRatio, memoryOvercommitRatio, true);

                    // if not reserved, check the free capacity
                    if (!hostHasCapacity)
                        hostHasCapacity = _capacityMgr.checkIfHostHasCapacity(host, cpuRequested, ramRequested, false, cpuOvercommitRatio, memoryOvercommitRatio, true);
                    }

                boolean displayStorage = getDisplayStorageFromVmProfile(vmProfile);
                if (!hostHasCapacity || !hostHasCpuCapability) {
                    logger.debug("Cannot deploy VM [{}] to the last host [{}] because this host does not have enough capacity to deploy this VM.", vm, host);
                    return null;
                }
                Pod pod = _podDao.findById(host.getPodId());
                Cluster cluster = _clusterDao.findById(host.getClusterId());

                logger.debug("Last host [{}] of VM [{}] is UP and has enough capacity. Checking for suitable pools for this host under zone [{}], pod [{}] and cluster [{}].",
                        host, vm, dc, pod, cluster);

                if (DEPLOYMENT_PLANNING_SKIP_HYPERVISORS.contains(vm.getHypervisorType())) {
                    DeployDestination dest = new DeployDestination(dc, pod, cluster, host, new HashMap<>(), displayStorage);
                    logger.debug("Returning Deployment Destination: {}.", dest);
                    return dest;
                }

                // search for storage under the zone, pod, cluster
                // of
                // the last host.
                DataCenterDeployment lastPlan = new DataCenterDeployment(host.getDataCenterId(),
                        host.getPodId(), host.getClusterId(), host.getId(), plan.getPoolId(), null);
                Pair<Map<Volume, List<StoragePool>>, List<Volume>> result = findSuitablePoolsForVolumes(
                        vmProfile, lastPlan, avoids, HostAllocator.RETURN_UPTO_ALL);
                Map<Volume, List<StoragePool>> suitableVolumeStoragePools = result.first();
                List<Volume> readyAndReusedVolumes = result.second();

                // choose the potential pool for this VM for this
                // host
                if (suitableVolumeStoragePools.isEmpty()) {
                    logger.debug("Cannot find suitable storage pools in host [{}] to deploy VM [{}]", host, vm);
                    return null;
                }
                List<Host> suitableHosts = new ArrayList<>();
                suitableHosts.add(host);
                Pair<Host, Map<Volume, StoragePool>> potentialResources = findPotentialDeploymentResources(
                        suitableHosts, suitableVolumeStoragePools, avoids,
                        getPlannerUsage(planner, vmProfile, plan, avoids), readyAndReusedVolumes, plan.getPreferredHosts(), vm);
                if (potentialResources != null) {
                    Map<Volume, StoragePool> storageVolMap = potentialResources.second();
                    // remove the reused vol<->pool from
                    // destination, since we don't have to
                    // prepare
                    // this volume.
                    for (Volume vol : readyAndReusedVolumes) {
                        storageVolMap.remove(vol);
                    }
                    DeployDestination dest = new DeployDestination(dc, pod, cluster, host, storageVolMap, displayStorage);
                    logger.debug("Returning Deployment Destination: {}", dest);
                    return dest;
                }
            }
        }
        logger.debug("Cannot choose the last host to deploy this VM {}.", vm);
        return null;
    }

    private boolean canUseLastHost(HostVO host, ExcludeList avoids, DeploymentPlan plan, VirtualMachine vm, ServiceOffering offering, boolean volumesRequireEncryption) {
        if (host == null) {
            logger.warn("Could not find last host of VM [{}] with id [{}]. Skipping this and trying other available hosts.", vm, vm.getLastHostId());
            return false;
        }

        if (avoids.shouldAvoid(host)) {
            logger.warn("The last host [{}] of VM [{}] is in the avoid set. Skipping this and trying other available hosts.", host, vm);
            return false;
        }

        if (plan.getClusterId() != null && host.getClusterId() != null && !plan.getClusterId().equals(host.getClusterId())) {
            logger.debug("The last host [{}] of VM [{}] cannot be picked, as the plan [{}] specifies a different cluster [{}] to deploy this VM. Skipping this and trying other available hosts.", host, vm, plan.getClass().getSimpleName(), plan.getClusterId());
            return false;
        }

        if (_capacityMgr.checkIfHostReachMaxGuestLimit(host)) {
            logger.debug("Cannot deploy VM [{}] in the last host [{}] because this host already has the max number of running VMs (users and system VMs). Skipping this and trying other available hosts.",
                    vm, host);
            return false;
        }

        ServiceOfferingDetailsVO offeringDetails = _serviceOfferingDetailsDao.findDetail(offering.getId(), GPU.Keys.vgpuType.toString());
        ServiceOfferingDetailsVO groupName = _serviceOfferingDetailsDao.findDetail(offering.getId(), GPU.Keys.pciDevice.toString());
        if (offeringDetails != null && !_resourceMgr.isGPUDeviceAvailable(host, groupName.getValue(), offeringDetails.getValue())) {
            logger.debug("Cannot deploy VM [{}] in the last host [{}] because this host does not have the required GPU devices available. Skipping this and trying other available hosts.",
                    vm, host);
            return false;
        }

        if (volumesRequireEncryption && !Boolean.parseBoolean(host.getDetail(Host.HOST_VOLUME_ENCRYPTION))) {
            logger.warn("The last host of this VM {} does not support volume encryption, which is required by this VM.", host);
            return false;
        }
        return true;
    }

    private DeployDestination deployInSpecifiedHostWithoutHA(VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoids,
            DeploymentPlanner planner, VirtualMachine vm, DataCenter dc, String uefiFlag)
            throws InsufficientServerCapacityException {
        Long hostIdSpecified = plan.getHostId();
        logger.debug("DeploymentPlan [{}] has specified host [{}] without HA flag. Choosing this host to deploy VM [{}].", plan.getClass().getSimpleName(), hostIdSpecified, vm);

        HostVO host = _hostDao.findById(hostIdSpecified);
        if (host != null && StringUtils.isNotBlank(uefiFlag) && "yes".equalsIgnoreCase(uefiFlag)) {
            _hostDao.loadDetails(host);
            if (MapUtils.isNotEmpty(host.getDetails()) && host.getDetails().containsKey(Host.HOST_UEFI_ENABLE) && "false".equalsIgnoreCase(host.getDetails().get(Host.HOST_UEFI_ENABLE))) {
                logger.debug("Cannot deploy VM [{}] to specified host [{}] because this host does not support UEFI VM deployment, returning.", vm, host);
                return null;
            }
        }
        if (host == null) {
            logger.debug("Cannot deploy VM [{}] to host [{}] because this host cannot be found.", vm, hostIdSpecified);
            return null;
        }
        if (avoids.shouldAvoid(host)) {
            logger.debug("Cannot deploy VM [{}] to host [{}] because this host is in the avoid set.", vm, host);
            return null;
        }

        Pod pod = _podDao.findById(host.getPodId());
        Cluster cluster = _clusterDao.findById(host.getClusterId());

        logger.debug("Trying to find suitable pools for host [{}] under pod [{}], cluster [{}] and zone [{}], to deploy VM [{}].",
                host, dc, pod, cluster, vm);

        boolean displayStorage = getDisplayStorageFromVmProfile(vmProfile);
        if (DEPLOYMENT_PLANNING_SKIP_HYPERVISORS.contains(vm.getHypervisorType())) {
            DeployDestination dest = new DeployDestination(dc, pod, cluster, host, new HashMap<>(),
                    displayStorage);
            logger.debug("Returning Deployment Destination: {}.", dest);
            return dest;
        }

        DataCenterDeployment lastPlan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(),
                host.getClusterId(), hostIdSpecified, plan.getPoolId(), null, plan.getReservationContext());

        Pair<Map<Volume, List<StoragePool>>, List<Volume>> result = findSuitablePoolsForVolumes(vmProfile, lastPlan,
                avoids, HostAllocator.RETURN_UPTO_ALL);
        Map<Volume, List<StoragePool>> suitableVolumeStoragePools = result.first();
        List<Volume> readyAndReusedVolumes = result.second();

        if (!suitableVolumeStoragePools.isEmpty()) {
            List<Host> suitableHosts = new ArrayList<>();
            suitableHosts.add(host);
            Pair<Host, Map<Volume, StoragePool>> potentialResources = findPotentialDeploymentResources(suitableHosts,
                    suitableVolumeStoragePools, avoids, getPlannerUsage(planner, vmProfile, plan, avoids),
                    readyAndReusedVolumes, plan.getPreferredHosts(), vm);
            if (potentialResources != null) {
                Map<Volume, StoragePool> storageVolMap = potentialResources.second();
                for (Volume vol : readyAndReusedVolumes) {
                    storageVolMap.remove(vol);
                }
                DeployDestination dest = new DeployDestination(dc, pod, cluster, host, storageVolMap, displayStorage);
                logger.debug("Returning Deployment Destination: {}", dest);
                return dest;
            }
        }
        logger.debug("Cannot deploy VM [{}] under host [{}], because no suitable pools were found.", vmProfile, host);
        return null;
    }

    protected Volume getRootVolume(List<? extends Volume> volumes) {
        for (Volume volume : volumes) {
            if (volume.getVolumeType() == Volume.Type.ROOT) {
                return volume;
            }
        }
        return null;
    }

    protected boolean anyVolumeRequiresEncryption(List<? extends Volume> volumes) {
        for (Volume volume : volumes) {
            if (volume.getPassphraseId() != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeployAsIs(VirtualMachine vm) {
        long templateId = vm.getTemplateId();
        VMTemplateVO template = templateDao.findById(templateId);
        return template != null && template.isDeployAsIs();
    }

    /**
     * Display storage in the logs by default if the template is not deploy-as-is.
     */
    private boolean getDisplayStorageFromVmProfile(VirtualMachineProfile vmProfile) {
        return vmProfile == null || vmProfile.getTemplate() == null || !vmProfile.getTemplate().isDeployAsIs();
    }

    /**
     * Adds disabled resources (Data centers, Pods, Clusters, and hosts) to exclude
     * list (avoid) in case of disabled state.
     */
    public void avoidDisabledResources(VirtualMachineProfile vmProfile, DataCenter dc, ExcludeList avoids) {
        if (vmProfile.getType().isUsedBySystem() && isRouterDeployableInDisabledResources()) {
            return;
        }

        VMInstanceVO vm = _vmInstanceDao.findById(vmProfile.getId());
        AccountVO owner = accountDao.findById(vm.getAccountId());
        boolean isOwnerRoleIdAdmin = owner != null && owner.getRoleId() != null && owner.getRoleId() == ADMIN_ACCOUNT_ROLE_ID;

        if (isOwnerRoleIdAdmin && isAdminVmDeployableInDisabledResources()) {
            return;
        }

        avoidDisabledDataCenters(dc, avoids);
        avoidDisabledPods(dc, avoids);
        avoidDisabledClusters(dc, avoids);
        avoidDisabledHosts(dc, avoids);
    }

    /**
     * Returns the value of the ConfigKey 'allow.router.on.disabled.resources'.
     * @note this method allows mocking and testing with the respective ConfigKey parameter.
     */
    protected boolean isRouterDeployableInDisabledResources() {
        return allowRouterOnDisabledResource.value();
    }

    /**
     * Returns the value of the ConfigKey 'allow.admin.vm.on.disabled.resources'.
     * @note this method allows mocking and testing with the respective ConfigKey parameter.
     */
    protected boolean isAdminVmDeployableInDisabledResources() {
        return allowAdminVmOnDisabledResource.value();
    }

    /**
     * Adds disabled Hosts to the ExcludeList in order to avoid them at the deployment planner.
     */
    protected void avoidDisabledHosts(DataCenter dc, ExcludeList avoids) {
        List<Long> disabledHostIds = _hostDao.listDisabledIdsByDataCenterId(dc.getId());
        logger.debug("Adding hosts {} of datacenter [{}] to the avoid set, because these hosts are in the Disabled state.",
                StringUtils.join(disabledHostIds), dc.getUuid());
        disabledHostIds.forEach(avoids::addHost);
    }

    /**
     * Adds disabled Clusters to the ExcludeList in order to avoid them at the deployment planner.
     */
    protected void avoidDisabledClusters(DataCenter dc, ExcludeList avoids) {
        List<Long> pods = _podDao.listAllPods(dc.getId());
        for (Long podId : pods) {
            List<Long> disabledClusters = _clusterDao.listDisabledClusters(dc.getId(), podId);
            logger.debug("Adding clusters [{}] of pod [{}] to the void set because these clusters are in the Disabled state.",
                    StringUtils.join(disabledClusters, ", "), podId);
            avoids.addClusterList(disabledClusters);
        }
    }

    /**
     * Adds disabled Pods to the ExcludeList in order to avoid them at the deployment planner.
     */
    protected void avoidDisabledPods(DataCenter dc, ExcludeList avoids) {
        List<Long> disabledPods = _podDao.listDisabledPods(dc.getId());
        logger.debug("Adding pods [{}] to the avoid set because these pods are in the Disabled state.", StringUtils.join(disabledPods, ", "));
        avoids.addPodList(disabledPods);
    }

    /**
     * Adds disabled Data Centers (Zones) to the ExcludeList in order to avoid them at the deployment planner.
     */
    protected void avoidDisabledDataCenters(DataCenter dc, ExcludeList avoids) {
        if (dc.getAllocationState() == Grouping.AllocationState.Disabled) {
            logger.debug("Adding datacenter [{}] to the avoid set because this datacenter is in Disabled state.", dc);
            avoids.addDataCenter(dc.getId());
        }
    }

    @Override
    public DeploymentPlanner getDeploymentPlannerByName(String plannerName) {
        if (plannerName != null) {
            for (DeploymentPlanner plannerInList : _planners) {
                if (plannerName.equalsIgnoreCase(plannerInList.getName())) {
                    return plannerInList;
                }
            }
        }

        return null;
    }

    protected boolean checkVmProfileAndHost(final VirtualMachineProfile vmProfile, final HostVO host) {
        ServiceOffering offering = vmProfile.getServiceOffering();
        VirtualMachineTemplate template = vmProfile.getTemplate();
        if (offering.getHostTag() != null || template.getTemplateTag() != null) {
            _hostDao.loadHostTags(host);
            if (!host.checkHostServiceOfferingAndTemplateTags(offering, template, UserVmManager.getStrictHostTags())) {
                logger.debug("Service Offering host tag or template tag does not match the last host of this VM");
                return false;
            }
        }
        long guestOSId = vmProfile.getTemplate().getGuestOSId();
        GuestOSVO guestOS = _guestOSDao.findById(guestOSId);
        if (guestOS != null) {
            long guestOSCategoryId = guestOS.getCategoryId();
            DetailVO hostDetail = _hostDetailsDao.findDetail(host.getId(), "guest.os.category.id");
            if (hostDetail != null) {
                String guestOSCategoryIdString = hostDetail.getValue();
                if (String.valueOf(guestOSCategoryId) != guestOSCategoryIdString) {
                    logger.debug("The last host has different guest.os.category.id than guest os category of VM, skipping");
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void checkForNonDedicatedResources(VirtualMachineProfile vmProfile, DataCenter dc, ExcludeList avoids) {
        boolean isExplicit = false;
        VirtualMachine vm = vmProfile.getVirtualMachine();

        // check if zone is dedicated. if yes check if vm owner has access to it.
        DedicatedResourceVO dedicatedZone = _dedicatedDao.findByZoneId(dc.getId());
        if (dedicatedZone != null && !_accountMgr.isRootAdmin(vmProfile.getOwner().getId())) {
            long accountDomainId = vmProfile.getOwner().getDomainId();
            long accountId = vmProfile.getOwner().getAccountId();
            logger.debug("Zone [{}] is dedicated. Checking if account [{}] in domain [{}] can use this zone to deploy VM [{}].",
                    dedicatedZone.getUuid(), accountId, accountDomainId, vmProfile);

            // If a zone is dedicated to an account then all hosts in this zone
            // will be explicitly dedicated to
            // that account. So there won't be any shared hosts in the zone, the
            // only way to deploy vms from that
            // account will be to use explicit dedication affinity group.
            if (dedicatedZone.getAccountId() != null) {
                if (dedicatedZone.getAccountId().equals(accountId)) {
                    return;
                } else {
                    throw new CloudRuntimeException("Failed to deploy VM, Zone " + dc + " not available for the user account " + vmProfile.getOwner());
                }
            }

            // if zone is dedicated to a domain. Check owner's access to the
            // domain level dedication group
            if (!_affinityGroupService.isAffinityGroupAvailableInDomain(dedicatedZone.getAffinityGroupId(), accountDomainId)) {
                throw new CloudRuntimeException("Failed to deploy VM, Zone " + dc + " not available for the user domain " + vmProfile.getOwner());
            }
        }

        // check affinity group of type Explicit dedication exists. If No put
        // dedicated pod/cluster/host in avoid list
        List<AffinityGroupVMMapVO> vmGroupMappings = _affinityGroupVMMapDao.findByVmIdType(vm.getId(), "ExplicitDedication");

        if (vmGroupMappings != null && !vmGroupMappings.isEmpty()) {
            isExplicit = true;
        }

        List<Long> allPodsInDc = _podDao.listAllPods(dc.getId());
        List<Long> allDedicatedPods = _dedicatedDao.listAllPods();
        allPodsInDc.retainAll(allDedicatedPods);

        List<Long> allClustersInDc = _clusterDao.listAllClusterIds(dc.getId());
        List<Long> allDedicatedClusters = _dedicatedDao.listAllClusters();
        allClustersInDc.retainAll(allDedicatedClusters);

        List<Long> allHostsInDc = _hostDao.listAllHosts(dc.getId());
        List<Long> allDedicatedHosts = _dedicatedDao.listAllHosts();
        allHostsInDc.retainAll(allDedicatedHosts);

        //Only when the type is instance VM and not explicitly dedicated.
        if (vm.getType() == VirtualMachine.Type.User && !isExplicit) {
            findAvoidSetForNonExplicitUserVM(avoids, vm, allPodsInDc, allClustersInDc, allHostsInDc);
        }

        //Handle the Virtual Router Case
        //No need to check the isExplicit. As both the cases are handled.
        if (vm.getType() == VirtualMachine.Type.DomainRouter) {
            findAvoiSetForRouterVM(avoids, vm, allPodsInDc, allClustersInDc, allHostsInDc);
        }
    }

    private void findAvoiSetForRouterVM(ExcludeList avoids, VirtualMachine vm, List<Long> allPodsInDc, List<Long> allClustersInDc, List<Long> allHostsInDc) {
        long vmAccountId = vm.getAccountId();
        long vmDomainId = vm.getDomainId();

        List<Long> allPodsFromDedicatedID = new ArrayList<>();
        List<Long> allClustersFromDedicatedID = new ArrayList<>();
        List<Long> allHostsFromDedicatedID = new ArrayList<>();

        List<AffinityGroupDomainMapVO> domainGroupMappings = _affinityGroupDomainMapDao.listByDomain(vmDomainId);

        List<DedicatedResourceVO> tempStorage;

        if (domainGroupMappings == null || domainGroupMappings.isEmpty()) {
            tempStorage = _dedicatedDao.searchDedicatedPods(null, vmDomainId, vmAccountId, null,
                    new Filter(DedicatedResourceVO.class, "id", true, 0L, 1L)).first();

            for (DedicatedResourceVO vo : tempStorage) {
                allPodsFromDedicatedID.add(vo.getPodId());
            }

            tempStorage.clear();
            tempStorage = _dedicatedDao.searchDedicatedClusters(null, vmDomainId, vmAccountId, null,
                    new Filter(DedicatedResourceVO.class, "id", true, 0L, 1L)).first();

            for (DedicatedResourceVO vo : tempStorage) {
                allClustersFromDedicatedID.add(vo.getClusterId());
            }

            tempStorage.clear();
            tempStorage = _dedicatedDao.searchDedicatedHosts(null, vmDomainId, vmAccountId, null,
                    new Filter(DedicatedResourceVO.class, "id", true, 0L, 1L)).first();

            for (DedicatedResourceVO vo : tempStorage) {
                allHostsFromDedicatedID.add(vo.getHostId());
            }

            allPodsInDc.removeAll(allPodsFromDedicatedID);
            allClustersInDc.removeAll(allClustersFromDedicatedID);
            allHostsInDc.removeAll(allHostsFromDedicatedID);
        } else {
            tempStorage = _dedicatedDao.searchDedicatedPods(null, vmDomainId, null, null,
                    new Filter(DedicatedResourceVO.class, "id", true, 0L, 1L)).first();

            for (DedicatedResourceVO vo : tempStorage) {
                allPodsFromDedicatedID.add(vo.getPodId());
            }

            tempStorage.clear();
            tempStorage = _dedicatedDao.searchDedicatedClusters(null, vmDomainId, null, null,
                    new Filter(DedicatedResourceVO.class, "id", true, 0L, 1L)).first();

            for (DedicatedResourceVO vo : tempStorage) {
                allClustersFromDedicatedID.add(vo.getClusterId());
            }

            tempStorage.clear();
            tempStorage = _dedicatedDao.searchDedicatedHosts(null, vmDomainId, null, null,
                    new Filter(DedicatedResourceVO.class, "id", true, 0L, 1L)).first();

            for (DedicatedResourceVO vo : tempStorage) {
                allHostsFromDedicatedID.add(vo.getHostId());
            }

            allPodsInDc.removeAll(allPodsFromDedicatedID);
            allClustersInDc.removeAll(allClustersFromDedicatedID);
            allHostsInDc.removeAll(allHostsFromDedicatedID);
        }

        logger.debug(() -> LogUtils.logGsonWithoutException("Adding pods [%s], clusters [%s] and hosts [%s] to the avoid list in the deploy process of VR VM [%s], "
                        + "because this VM is not dedicated to this components.", allPodsInDc, allClustersInDc, allHostsInDc, vm));
        avoids.addPodList(allPodsInDc);
        avoids.addClusterList(allClustersInDc);
        avoids.addHostList(allHostsInDc);
    }

    private void findAvoidSetForNonExplicitUserVM(ExcludeList avoids, VirtualMachine vm, List<Long> allPodsInDc, List<Long> allClustersInDc, List<Long> allHostsInDc) {
        logger.debug(() -> LogUtils.logGsonWithoutException("Adding pods [%s], clusters [%s] and hosts [%s] to the avoid list in the deploy process of user VM [%s], "
                        + "because this VM is not explicitly dedicated to these components.", allPodsInDc, allClustersInDc, allHostsInDc, vm));
        avoids.addPodList(allPodsInDc);
        avoids.addClusterList(allClustersInDc);
        avoids.addHostList(allHostsInDc);
    }

    private void resetAvoidSet(ExcludeList avoidSet, ExcludeList removeSet) {
        if (avoidSet.getDataCentersToAvoid() != null && removeSet.getDataCentersToAvoid() != null) {
            avoidSet.getDataCentersToAvoid().removeAll(removeSet.getDataCentersToAvoid());
        }
        if (avoidSet.getPodsToAvoid() != null && removeSet.getPodsToAvoid() != null) {
            avoidSet.getPodsToAvoid().removeAll(removeSet.getPodsToAvoid());
        }
        if (avoidSet.getClustersToAvoid() != null && removeSet.getClustersToAvoid() != null) {
            avoidSet.getClustersToAvoid().removeAll(removeSet.getClustersToAvoid());
        }
        if (avoidSet.getHostsToAvoid() != null && removeSet.getHostsToAvoid() != null) {
            avoidSet.getHostsToAvoid().removeAll(removeSet.getHostsToAvoid());
        }
        if (avoidSet.getPoolsToAvoid() != null && removeSet.getPoolsToAvoid() != null) {
            avoidSet.getPoolsToAvoid().removeAll(removeSet.getPoolsToAvoid());
        }
    }

    private PlannerResourceUsage getPlannerUsage(DeploymentPlanner planner, VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoids)
            throws InsufficientServerCapacityException {
        if (planner != null && planner instanceof DeploymentClusterPlanner) {
            return ((DeploymentClusterPlanner)planner).getResourceUsage(vmProfile, plan, avoids);
        } else {
            return DeploymentPlanner.PlannerResourceUsage.Shared;
        }

    }

    @DB
    protected boolean checkIfHostFitsPlannerUsage(final Host host, final PlannerResourceUsage resourceUsageRequired) {
        // TODO Auto-generated method stub
        // check if this host has been picked up by some other planner
        // exclusively
        // if planner can work with shared host, check if this host has
        // been marked as 'shared'
        // else if planner needs dedicated host,
        PlannerHostReservationVO reservationEntry = _plannerHostReserveDao.findByHostId(host.getId());
        if (reservationEntry != null) {
            final long id = reservationEntry.getId();
            PlannerResourceUsage hostResourceType = reservationEntry.getResourceUsage();

            if (hostResourceType != null) {
                if (hostResourceType == resourceUsageRequired) {
                    return true;
                } else {
                    logger.debug("Cannot use this host for usage: " + resourceUsageRequired + ", since this host has been reserved for planner usage : " +
                            hostResourceType);
                    return false;
                }
            } else {
                final PlannerResourceUsage hostResourceTypeFinal = hostResourceType;
                // reserve the host for required resourceType
                // let us lock the reservation entry before updating.
                return Transaction.execute(new TransactionCallback<>() {
                    @Override
                    public Boolean doInTransaction(TransactionStatus status) {
                        final PlannerHostReservationVO lockedEntry = _plannerHostReserveDao.lockRow(id, true);
                        if (lockedEntry == null) {
                            logger.error("Unable to lock the host entry for reservation, host: {}", host);
                            return false;
                        }
                        // check before updating
                        if (lockedEntry.getResourceUsage() == null) {
                            lockedEntry.setResourceUsage(resourceUsageRequired);
                            _plannerHostReserveDao.persist(lockedEntry);
                            return true;
                        } else {
                            // someone updated it earlier. check if we can still use it
                            if (lockedEntry.getResourceUsage() == resourceUsageRequired) {
                                return true;
                            } else {
                                logger.debug("Cannot use this host for usage: " + resourceUsageRequired + ", since this host has been reserved for planner usage : " +
                                        hostResourceTypeFinal);
                                return false;
                            }
                        }
                    }
                });

            }

        }

        return false;
    }

    @DB
    public boolean checkHostReservationRelease(final Host host) {

        if (host != null) {
            PlannerHostReservationVO reservationEntry = _plannerHostReserveDao.findByHostId(host.getId());
            if (reservationEntry != null && reservationEntry.getResourceUsage() != null) {

                // check if any VMs are starting or running on this host
                List<VMInstanceVO> vms = _vmInstanceDao.listUpByHostId(host.getId());
                if (vms.size() > 0) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Cannot release reservation, Found {} VMs Running on host {}", vms.size(), host);
                    }
                    return false;
                }

                List<VMInstanceVO> vmsByLastHostId = _vmInstanceDao.listByLastHostId(host.getId());
                if (vmsByLastHostId.size() > 0) {
                    // check if any VMs are within skip.counting.hours, if yes
                    // we
                    // cannot release the host
                    for (VMInstanceVO stoppedVM : vmsByLastHostId) {
                        long secondsSinceLastUpdate = (DateUtil.currentGMTTime().getTime() - stoppedVM.getUpdateTime().getTime()) / 1000;
                        if (secondsSinceLastUpdate < _vmCapacityReleaseInterval) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Cannot release reservation, Found VM: {} Stopped but reserved on host {}", stoppedVM, host);
                            }
                            return false;
                        }
                    }
                }

                // check if any VMs are stopping on or migrating to this host
                List<VMInstanceVO> vmsStoppingMigratingByHostId = _vmInstanceDao.findByHostInStates(host.getId(), State.Stopping, State.Migrating, State.Starting);
                if (vmsStoppingMigratingByHostId.size() > 0) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Cannot release reservation, Found {} VMs stopping/migrating/starting on host {}", vmsStoppingMigratingByHostId.size(), host);
                    }
                    return false;
                }

                // check if any VMs are in starting state with no hostId set yet
                // -
                // just ignore host release to avoid race condition
                List<VMInstanceVO> vmsStartingNoHost = _vmInstanceDao.listStartingWithNoHostId();

                if (vmsStartingNoHost.size() > 0) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Cannot release reservation, Found " + vms.size() + " VMs starting as of now and no hostId yet stored");
                    }
                    return false;
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("Host has no VMs associated, releasing the planner reservation for host {}", host);
                }

                final long id = reservationEntry.getId();

                return Transaction.execute(new TransactionCallback<>() {
                    @Override
                    public Boolean doInTransaction(TransactionStatus status) {
                        final PlannerHostReservationVO lockedEntry = _plannerHostReserveDao.lockRow(id, true);
                        if (lockedEntry == null) {
                            logger.error("Unable to lock the host entry for reservation, host: {}", host);
                            return false;
                        }
                        // check before updating
                        if (lockedEntry.getResourceUsage() != null) {
                            lockedEntry.setResourceUsage(null);
                            _plannerHostReserveDao.persist(lockedEntry);
                            return true;
                        }

                        return false;
                    }
                });
            }

        }
        return false;
    }

    class HostReservationReleaseChecker extends ManagedContextTimerTask {
        @Override
        protected void runInContext() {
            try {
                logger.debug("Checking if any host reservation can be released ... ");
                checkHostReservations();
                logger.debug("Done running HostReservationReleaseChecker ... ");
            } catch (Throwable t) {
                logger.error("Exception in HostReservationReleaseChecker", t);
            }
        }
    }

    private void checkHostReservations() {
        List<PlannerHostReservationVO> reservedHosts = _plannerHostReserveDao.listAllReservedHosts();
        List<HostVO> hosts = _hostDao.listByIds(reservedHosts
                .stream()
                .map(PlannerHostReservationVO::getHostId)
                .collect(Collectors.toList()));
        for (HostVO host : hosts) {
            if (host != null && host.getManagementServerId() != null && host.getManagementServerId() == _nodeId) {
                checkHostReservationRelease(host);
            }
        }

    }

    @Override
    public boolean processAnswers(long agentId, long seq, Answer[] answers) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean processCommands(long agentId, long seq, Command[] commands) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public AgentControlAnswer processControlCommand(long agentId, AgentControlCommand cmd) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void processHostAdded(long hostId) {
    }

    @Override
    public void processConnect(Host host, StartupCommand cmd, boolean forRebalance) throws ConnectionException {
        if (!(cmd instanceof StartupRoutingCommand)) {
            return;
        }

        PlannerHostReservationVO reservationEntry = _plannerHostReserveDao.findByHostId(host.getId());
        if (reservationEntry == null) {
            // record the host in this table
            PlannerHostReservationVO newHost = new PlannerHostReservationVO(host.getId(), host.getDataCenterId(), host.getPodId(), host.getClusterId());
            _plannerHostReserveDao.persist(newHost);
        }

    }

    @Override
    public boolean processDisconnect(long agentId, Status state) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void processHostAboutToBeRemoved(long hostId) {
    }

    @Override
    public void processHostRemoved(long hostId, long clusterId) {
    }

    @Override
    public boolean isRecurring() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public int getTimeout() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean processTimeout(long agentId, long seq) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _agentMgr.registerForHostEvents(this, true, false, true);
        VirtualMachine.State.getStateMachine().registerListener(this);
        _messageBus.subscribe("VM_ReservedCapacity_Free", new MessageSubscriber() {
            @Override
            public void onPublishMessage(String senderAddress, String subject, Object obj) {
                VMInstanceVO vm = ((VMInstanceVO)obj);
                Host host = _hostDao.findById(vm.getLastHostId());
                logger.debug("MessageBus message: host reserved capacity released for VM: {}, checking if host reservation can be released for host:{}", vm, host);
                checkHostReservationRelease(host);
            }
        });

        _vmCapacityReleaseInterval = NumbersUtil.parseInt(_configDao.getValue(Config.CapacitySkipcountingHours.key()), 3600);

        String hostReservationReleasePeriod = _configDao.getValue(Config.HostReservationReleasePeriod.key());
        if (hostReservationReleasePeriod != null) {
            _hostReservationReleasePeriod = Long.parseLong(hostReservationReleasePeriod);
            if (_hostReservationReleasePeriod <= 0)
                _hostReservationReleasePeriod = Long.parseLong(Config.HostReservationReleasePeriod.getDefaultValue());
        }

        _timer = new Timer("HostReservationReleaseChecker");

        _nodeId = ManagementServerNode.getManagementServerId();

        return super.configure(name, params);
    }

    @Override
    public boolean start() {
        _timer.schedule(new HostReservationReleaseChecker(), INITIAL_RESERVATION_RELEASE_CHECKER_DELAY, _hostReservationReleasePeriod);
        cleanupVMReservations();
        return true;
    }

    @Override
    public boolean stop() {
        _timer.cancel();
        return true;
    }

    @Override
    public void cleanupVMReservations() {
        List<VMReservationVO> reservations = _reservationDao.listAll();

        for (VMReservationVO reserv : reservations) {
            VMInstanceVO vm = _vmInstanceDao.findById(reserv.getVmId());
            if (vm != null) {
                if (vm.getState() == State.Starting || (vm.getState() == State.Stopped && vm.getLastHostId() == null)) {
                    continue;
                } else {
                    // delete reservation
                    _reservationDao.remove(reserv.getId());
                }
            } else {
                // delete reservation
                _reservationDao.remove(reserv.getId());
            }
        }
    }

    // /refactoring planner methods
    private DeployDestination checkClustersforDestination(List<Long> clusterList, VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoid, DataCenter dc,
            DeploymentPlanner.PlannerResourceUsage resourceUsageRequired, ExcludeList plannerAvoidOutput) {

        if (logger.isTraceEnabled()) {
            logger.trace("ClusterId List to consider: {}.", clusterList);
        }

        for (Long clusterId : clusterList) {
            ClusterVO clusterVO = _clusterDao.findById(clusterId);

            if (clusterVO.getHypervisorType() != vmProfile.getHypervisorType()) {
                logger.debug("Adding cluster [{}] to the avoid set because the cluster's hypervisor [{}] does not match the VM [{}] hypervisor: [{}]. Skipping this cluster.",
                        clusterVO, clusterVO.getHypervisorType().name(), vmProfile, vmProfile.getHypervisorType().name());
                avoid.addCluster(clusterVO.getId());
                continue;
            }

            Pod pod = _podDao.findById(clusterVO.getPodId());
            logger.debug("Checking resources in Cluster: " + clusterVO + " under Pod: " + pod);
            // search for resources(hosts and storage) under this zone, pod,
            // cluster.
            DataCenterDeployment potentialPlan =
                    new DataCenterDeployment(plan.getDataCenterId(), clusterVO.getPodId(), clusterVO.getId(), null, plan.getPoolId(), null, plan.getReservationContext());
            potentialPlan.setHostPriorities(plan.getHostPriorities());

            if (CollectionUtils.isNotEmpty(avoid.getPodsToAvoid()) && avoid.getPodsToAvoid().contains(pod.getId())) {
                logger.debug("The cluster is in a disabled pod : " + pod);
            } else {
                // find suitable hosts under this cluster, need as many hosts as we
                // get.
                List<Host> suitableHosts = findSuitableHosts(vmProfile, potentialPlan, avoid, HostAllocator.RETURN_UPTO_ALL);
                // if found suitable hosts in this cluster, find suitable storage
                // pools for each volume of the VM
                if (CollectionUtils.isNotEmpty(suitableHosts)) {
                    if (DEPLOYMENT_PLANNING_SKIP_HYPERVISORS.contains(vmProfile.getHypervisorType())) {
                        DeployDestination dest = new DeployDestination(dc, pod, clusterVO, suitableHosts.get(0));
                        return dest;
                    }

                    Pair<Map<Volume, List<StoragePool>>, List<Volume>> result = findSuitablePoolsForVolumes(vmProfile, potentialPlan, avoid, StoragePoolAllocator.RETURN_UPTO_ALL);
                    Map<Volume, List<StoragePool>> suitableVolumeStoragePools = result.first();
                    List<Volume> readyAndReusedVolumes = result.second();

                    // choose the potential host and pool for the VM
                    if (!suitableVolumeStoragePools.isEmpty()) {
                        Pair<Host, Map<Volume, StoragePool>> potentialResources = findPotentialDeploymentResources(suitableHosts, suitableVolumeStoragePools, avoid,
                                resourceUsageRequired, readyAndReusedVolumes, plan.getPreferredHosts(), vmProfile.getVirtualMachine());
                        if (potentialResources != null) {
                            Host host = potentialResources.first();
                            Map<Volume, StoragePool> storageVolMap = potentialResources.second();
                            // remove the reused vol<->pool from destination, since
                            // we don't have to prepare this volume.
                            for (Volume vol : readyAndReusedVolumes) {
                                storageVolMap.remove(vol);
                            }
                            boolean displayStorage = getDisplayStorageFromVmProfile(vmProfile);
                            DeployDestination dest = new DeployDestination(dc, pod, clusterVO, host, storageVolMap, displayStorage);
                            logger.debug("Returning Deployment Destination: " + dest);
                            return dest;
                        }
                    } else {
                        logger.debug("No suitable storagePools found under this Cluster: " + clusterVO);
                    }
                } else {
                    logger.debug("No suitable hosts found under this Cluster: " + clusterVO);
                }
            }

            if (canAvoidCluster(clusterVO, avoid, plannerAvoidOutput, vmProfile)) {
                avoid.addCluster(clusterVO.getId());
            }
        }
        logger.debug("Could not find suitable Deployment Destination for this VM under any clusters, returning. ");
        return null;
    }

    private boolean canAvoidCluster(Cluster clusterVO, ExcludeList avoids, ExcludeList plannerAvoidOutput, VirtualMachineProfile vmProfile) {

        ExcludeList allocatorAvoidOutput =
                new ExcludeList(avoids.getDataCentersToAvoid(), avoids.getPodsToAvoid(), avoids.getClustersToAvoid(), avoids.getHostsToAvoid(), avoids.getPoolsToAvoid());

        // remove any hosts/pools that the planners might have added
        // to get the list of hosts/pools that Allocators flagged as 'avoid'

        resetAvoidSet(allocatorAvoidOutput, plannerAvoidOutput);

        // if all hosts or all pools in the cluster are in avoid set after this
        // pass, then put the cluster in avoid set.
        boolean avoidAllHosts = true;
        boolean avoidAllPools = true;
        boolean avoidAllLocalPools = true;
        boolean avoidAllSharedPools = true;

        List<HostVO> allhostsInCluster =
                _hostDao.listAllUpAndEnabledNonHAHosts(Host.Type.Routing, clusterVO.getId(), clusterVO.getPodId(), clusterVO.getDataCenterId(), null);
        for (HostVO host : allhostsInCluster) {
            if (!allocatorAvoidOutput.shouldAvoid(host)) {
                // there's some host in the cluster that is not yet in avoid set
                avoidAllHosts = false;
                break;
            }
        }

        // all hosts in avoid set, avoid the cluster. Otherwise check the pools
        if (avoidAllHosts) {
            return true;
        }

        // Cluster can be put in avoid set in following scenarios:
        // 1. If storage allocators haven't put any pools in avoid set means either no pools in cluster
        // or pools not suitable for the allocators to handle or there is no
        // linkage of any suitable host to any of the pools in cluster
        // 2. If all 'shared' or 'local' pools are in avoid set
        if  (allocatorAvoidOutput.getPoolsToAvoid() != null && !allocatorAvoidOutput.getPoolsToAvoid().isEmpty()) {

            Pair<Boolean, Boolean> storageRequirements = findVMStorageRequirements(vmProfile);
            boolean vmRequiresSharedStorage = storageRequirements.first();
            boolean vmRequiresLocalStorege = storageRequirements.second();

            if (vmRequiresSharedStorage) {
                // check shared pools
                List<StoragePoolVO> allPoolsInCluster = _storagePoolDao.findPoolsByTags(clusterVO.getDataCenterId(), clusterVO.getPodId(), clusterVO.getId(), ScopeType.CLUSTER, null, false, 0);
                for (StoragePoolVO pool : allPoolsInCluster) {
                    if (!allocatorAvoidOutput.shouldAvoid(pool)) {
                        // there's some pool in the cluster that is not yet in avoid set
                        avoidAllSharedPools = false;
                        break;
                    }
                }
            }

            if (vmRequiresLocalStorege) {
                // check local pools
                List<StoragePoolVO> allLocalPoolsInCluster =
                        _storagePoolDao.findLocalStoragePoolsByTags(clusterVO.getDataCenterId(), clusterVO.getPodId(), clusterVO.getId(), null, false);
                for (StoragePoolVO pool : allLocalPoolsInCluster) {
                    if (!allocatorAvoidOutput.shouldAvoid(pool)) {
                        // there's some pool in the cluster that is not yet
                        // in avoid set
                        avoidAllLocalPools = false;
                        break;
                    }
                }
            }

            if (vmRequiresSharedStorage && vmRequiresLocalStorege) {
                avoidAllPools = (avoidAllLocalPools || avoidAllSharedPools) ? true : false;
            } else if (vmRequiresSharedStorage) {
                avoidAllPools = avoidAllSharedPools;
            } else if (vmRequiresLocalStorege) {
                avoidAllPools = avoidAllLocalPools;
            }
        }

        if (avoidAllHosts || avoidAllPools) {
            return true;
        }
        return false;
    }

    private Pair<Boolean, Boolean> findVMStorageRequirements(VirtualMachineProfile vmProfile) {

        boolean requiresShared = false, requiresLocal = false;

        List<VolumeVO> volumesTobeCreated = _volsDao.findUsableVolumesForInstance(vmProfile.getId());

        // for each volume find whether shared or local pool is required
        for (VolumeVO toBeCreated : volumesTobeCreated) {
            DiskOfferingVO diskOffering = _diskOfferingDao.findById(toBeCreated.getDiskOfferingId());

            if (diskOffering != null) {
                if (diskOffering.isUseLocalStorage()) {
                    requiresLocal = true;
                } else {
                    requiresShared = true;
                }
            }
        }

        return new Pair<>(requiresShared, requiresLocal);
    }

    protected Pair<Host, Map<Volume, StoragePool>> findPotentialDeploymentResources(List<Host> suitableHosts, Map<Volume, List<StoragePool>> suitableVolumeStoragePools,
                                                                                    ExcludeList avoid, PlannerResourceUsage resourceUsageRequired, List<Volume> readyAndReusedVolumes, List<Long> preferredHosts, VirtualMachine vm) {
        logger.debug("Trying to find a potenial host and associated storage pools from the suitable host/pool lists for this VM");

        boolean hostCanAccessPool = false;
        boolean haveEnoughSpace = false;
        boolean hostAffinityCheck = false;

        if (readyAndReusedVolumes == null) {
            readyAndReusedVolumes = new ArrayList<>();
        }
        Map<Volume, StoragePool> storage = new HashMap<>();
        TreeSet<Volume> volumesOrderBySizeDesc = new TreeSet<>(new Comparator<>() {
            @Override
            public int compare(Volume v1, Volume v2) {
                if (v1.getSize() < v2.getSize())
                    return 1;
                else
                    return -1;
            }
        });
        volumesOrderBySizeDesc.addAll(suitableVolumeStoragePools.keySet());
        boolean multipleVolume = volumesOrderBySizeDesc.size() > 1;
        boolean deployAsIs = isDeployAsIs(vm);
        for (Host potentialHost : suitableHosts) {
            Map<StoragePool, List<Volume>> volumeAllocationMap = new HashMap<>();
            if (deployAsIs) {
                storage = new HashMap<>();
                // Find the common suitable pools
                logger.debug("Trying to allocate all the VM volumes to a single storage pool");
                Set<StoragePool> suitablePools = new HashSet<>();
                List<StoragePool> notAllowedPools = new ArrayList<>();
                for (List<StoragePool> pools : suitableVolumeStoragePools.values()) {
                    if (CollectionUtils.isEmpty(suitablePools)) {
                        // All the suitable pools of the first volume
                        suitablePools.addAll(pools);
                    } else {
                        for (StoragePool pool : pools) {
                            if (!suitablePools.contains(pool)) {
                                logger.debug("Storage pool " + pool + " not allowed for this VM");
                                notAllowedPools.add(pool);
                            }
                        }
                    }
                }
                suitablePools.removeAll(notAllowedPools);
                if (CollectionUtils.isEmpty(suitablePools)) {
                    logger.debug("Could not find a storage pool to fit all the VM volumes on this host");
                    continue;
                }

                List<Volume> allVolumes = new ArrayList<>();
                allVolumes.addAll(volumesOrderBySizeDesc);
                List<Pair<Volume, DiskProfile>> volumeDiskProfilePair = getVolumeDiskProfilePairs(allVolumes);

                for (StoragePool storagePool : suitablePools) {
                    haveEnoughSpace = false;
                    hostCanAccessPool = false;
                    hostAffinityCheck = checkAffinity(potentialHost, preferredHosts);
                    if (hostCanAccessSPool(potentialHost, storagePool)) {
                        hostCanAccessPool = true;
                        if (potentialHost.getHypervisorType() == HypervisorType.VMware) {
                            try {
                                boolean isStoragePoolStoragepolicyComplaince = _storageMgr.isStoragePoolCompliantWithStoragePolicy(volumeDiskProfilePair, storagePool);
                                if (!isStoragePoolStoragepolicyComplaince) {
                                    continue;
                                }
                            } catch (StorageUnavailableException e) {
                                logger.warn("Could not verify storage policy complaince against storage pool {} due to exception {}", storagePool, e.getMessage());
                                continue;
                            }
                            haveEnoughSpace = true;
                        }
                    }
                    if (hostCanAccessPool && haveEnoughSpace && hostAffinityCheck) {
                        for (Volume vol : volumesOrderBySizeDesc) {
                            logger.debug("Found a suitable storage pool for all the VM volumes: {}", storagePool);
                            storage.put(vol, storagePool);
                        }
                        break;
                    }
                }
            } else {
                for (Volume vol : volumesOrderBySizeDesc) {
                    haveEnoughSpace = false;
                    logger.debug("Checking if host: {} can access any suitable storage pool for volume: {}", potentialHost, vol.getVolumeType());
                    List<StoragePool> volumePoolList = suitableVolumeStoragePools.get(vol);
                    hostCanAccessPool = false;
                    hostAffinityCheck = checkAffinity(potentialHost, preferredHosts);
                    for (StoragePool potentialSPool : volumePoolList) {
                        if (hostCanAccessSPool(potentialHost, potentialSPool)) {
                            hostCanAccessPool = true;
                            if (multipleVolume && !readyAndReusedVolumes.contains(vol)) {
                                List<Volume> requestVolumes = null;
                                if (volumeAllocationMap.containsKey(potentialSPool))
                                    requestVolumes = volumeAllocationMap.get(potentialSPool);
                                else
                                    requestVolumes = new ArrayList<>();
                                requestVolumes.add(vol);
                                List<Pair<Volume, DiskProfile>> volumeDiskProfilePair = getVolumeDiskProfilePairs(requestVolumes);
                                if (potentialHost.getHypervisorType() == HypervisorType.VMware) {
                                    try {
                                        boolean isStoragePoolStoragepolicyComplaince = _storageMgr.isStoragePoolCompliantWithStoragePolicy(volumeDiskProfilePair, potentialSPool);
                                        if (!isStoragePoolStoragepolicyComplaince) {
                                            continue;
                                        }
                                    } catch (StorageUnavailableException e) {
                                        logger.warn("Could not verify storage policy complaince against storage pool {} due to exception {}", potentialSPool, e.getMessage());
                                        continue;
                                    }
                                }

                                if (!_storageMgr.storagePoolHasEnoughIops(volumeDiskProfilePair, potentialSPool) ||
                                        !_storageMgr.storagePoolHasEnoughSpace(volumeDiskProfilePair, potentialSPool, potentialHost.getClusterId()))
                                    continue;
                                volumeAllocationMap.put(potentialSPool, requestVolumes);
                            }
                            storage.put(vol, potentialSPool);
                            haveEnoughSpace = true;
                            break;
                        }
                    }
                    if (!hostCanAccessPool) {
                        break;
                    }
                    if (!haveEnoughSpace) {
                        logger.warn("insufficient capacity to allocate all volumes");
                        break;
                    }
                    if (!hostAffinityCheck) {
                        logger.debug("Host affinity check failed");
                        break;
                    }
                }
            }

            HostVO potentialHostVO = _hostDao.findById(potentialHost.getId());
            _hostDao.loadDetails(potentialHostVO);

            boolean hostHasEncryption = Boolean.parseBoolean(potentialHostVO.getDetail(Host.HOST_VOLUME_ENCRYPTION));
            boolean hostMeetsEncryptionRequirements = !anyVolumeRequiresEncryption(new ArrayList<>(volumesOrderBySizeDesc)) || hostHasEncryption;
            boolean hostFitsPlannerUsage = checkIfHostFitsPlannerUsage(potentialHost, resourceUsageRequired);

            if (hostCanAccessPool && haveEnoughSpace && hostAffinityCheck && hostMeetsEncryptionRequirements && hostFitsPlannerUsage) {
                logger.debug("Found a potential host {} and associated storage pools for this VM", potentialHost);
                volumeAllocationMap.clear();
                return new Pair<>(potentialHost, storage);
            } else {
                logger.debug("Adding host [{}] to the avoid set because: can access Pool [{}], has enough space [{}], affinity check [{}], fits planner [{}] usage [{}].",
                        potentialHost, hostCanAccessPool, haveEnoughSpace, hostAffinityCheck, resourceUsageRequired.getClass().getSimpleName(), hostFitsPlannerUsage);

                if (!hostMeetsEncryptionRequirements) {
                    logger.debug("Potential host {} did not meet encryption requirements of all volumes", potentialHost);
                }
                avoid.addHost(potentialHost.getId());
            }
        }
        logger.debug("Could not find a potential host that has associated storage pools from the suitable host/pool lists for this VM");
        return null;
    }

    private  List<Pair<Volume, DiskProfile>> getVolumeDiskProfilePairs(List<Volume> volumes) {
        List<Pair<Volume, DiskProfile>> volumeDiskProfilePairs = new ArrayList<>();
        for (Volume volume: volumes) {
            DiskOfferingVO diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
            DiskProfile diskProfile = new DiskProfile(volume, diskOffering, _volsDao.getHypervisorType(volume.getId()));
            volumeDiskProfilePairs.add(new Pair<>(volume, diskProfile));
        }
        return volumeDiskProfilePairs;
    }

    /**
     * True if:
     * - Affinity is not enabled (preferred host is empty)
     * - Affinity is enabled and potential host is on the preferred hosts list
     *
     * False if not
     */
    @DB
    public boolean checkAffinity(Host potentialHost, List<Long> preferredHosts) {
        boolean hostAffinityEnabled = CollectionUtils.isNotEmpty(preferredHosts);
        boolean hostAffinityMatches = hostAffinityEnabled && preferredHosts.contains(potentialHost.getId());
        return !hostAffinityEnabled || hostAffinityMatches;
    }

    protected boolean hostCanAccessSPool(Host host, StoragePool pool) {
        if (!_storageMgr.checkIfHostAndStoragePoolHasCommonStorageAccessGroups(host, pool)) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("StoragePool %s and host %s does not have matching storage access groups", pool, host));
            }
            return false;
        }

        boolean hostCanAccessSPool = false;

        StoragePoolHostVO hostPoolLinkage = _poolHostDao.findByPoolHost(pool.getId(), host.getId());
        if (hostPoolLinkage != null && _storageMgr.canHostAccessStoragePool(host, pool)) {
            hostCanAccessSPool = true;
        }

        logger.debug("Host: {}{} access pool: {}", host, hostCanAccessSPool ? " can" : " cannot", pool);
        if (!hostCanAccessSPool) {
            if (_storageMgr.canHostPrepareStoragePoolAccess(host, pool)) {
                logger.debug("Host: {} can prepare access to pool: {}", host, pool);
                hostCanAccessSPool = true;
            } else {
                logger.debug("Host: {} cannot prepare access to pool: {}", host, pool);
            }
        }

        return hostCanAccessSPool;
    }

    protected List<Host> findSuitableHosts(VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoid, int returnUpTo) {
        List<Host> suitableHosts = new ArrayList<>();
        for (HostAllocator allocator : _hostAllocators) {
            suitableHosts = allocator.allocateTo(vmProfile, plan, Host.Type.Routing, avoid, returnUpTo);
            if (CollectionUtils.isNotEmpty(suitableHosts)) {
                break;
            }
        }

        if (CollectionUtils.isEmpty(suitableHosts)) {
            logger.debug("No suitable hosts found.");
        } else {
            reorderHostsByPriority(plan.getHostPriorities(), suitableHosts);
        }

        return suitableHosts;
    }

    @Override
    public void reorderHostsByPriority(Map<Long, Integer> priorities, List<Host> hosts) {
        logger.info("Re-ordering hosts {} by priorities {}", hosts, priorities);

        hosts.removeIf(host -> DataCenterDeployment.PROHIBITED_HOST_PRIORITY.equals(getHostPriority(priorities, host.getId())));

        Collections.sort(hosts, new Comparator<>() {
                    @Override
                    public int compare(Host host1, Host host2) {
                        int res = getHostPriority(priorities, host1.getId()).compareTo(getHostPriority(priorities, host2.getId()));
                        return -res;
                    }
                }
        );

        logger.info("Hosts after re-ordering are: {}", hosts);
    }

    private Integer getHostPriority(Map<Long, Integer> priorities, Long hostId) {
        return priorities.get(hostId) != null ? priorities.get(hostId) : DeploymentPlan.DEFAULT_HOST_PRIORITY;
    }

    protected Pair<Map<Volume, List<StoragePool>>, List<Volume>> findSuitablePoolsForVolumes(VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoid,
            int returnUpTo) {
        List<VolumeVO> volumesTobeCreated = _volsDao.findUsableVolumesForInstance(vmProfile.getId());
        Map<Volume, List<StoragePool>> suitableVolumeStoragePools = new HashMap<>();
        List<Volume> readyAndReusedVolumes = new ArrayList<>();

        // There should be at least the ROOT volume of the VM in usable state
        if (volumesTobeCreated.isEmpty()) {
            // OfflineVmwareMigration: find out what is wrong with the id of the vm we try to start
            throw new CloudRuntimeException("Unable to create deployment, no usable volumes found for the VM: " + vmProfile.getUuid());
        }

        // don't allow to start vm that doesn't have a root volume
        if (_volsDao.findByInstanceAndType(vmProfile.getId(), Volume.Type.ROOT).isEmpty()) {
            throw new CloudRuntimeException(String.format("Unable to deploy VM [%s] because the ROOT volume is missing.", vmProfile.getUuid()));
        }

        // for each volume find list of suitable storage pools by calling the
        // allocators
        Set<Long> originalAvoidPoolSet = new HashSet<>();
        if (avoid.getPoolsToAvoid() != null) {
            originalAvoidPoolSet.addAll(avoid.getPoolsToAvoid());
        }
        Set<Long> poolsToAvoidOutput = new HashSet<>(originalAvoidPoolSet);

        for (VolumeVO toBeCreated : volumesTobeCreated) {
            logger.debug("Checking suitable pools for volume [{}, {}] of VM [{}].", toBeCreated, toBeCreated.getVolumeType().name(), vmProfile);

            if (toBeCreated.getState() == Volume.State.Allocated && toBeCreated.getPoolId() != null) {
                toBeCreated.setPoolId(null);
                if (!_volsDao.update(toBeCreated.getId(), toBeCreated)) {
                    throw new CloudRuntimeException(String.format("Error updating volume [%s] to clear pool Id.", toBeCreated));
                }
                if (logger.isDebugEnabled()) {
                    logger.debug("Setting pool_id to NULL for volume id={} as it is in Allocated state", toBeCreated);
                }
            }
            // If the plan specifies a poolId, it means that this VM's ROOT
            // volume is ready and the pool should be reused.
            // In this case, also check if rest of the volumes are ready and can
            // be reused.
            if ((plan.getPoolId() != null || (toBeCreated.getVolumeType() == Volume.Type.DATADISK && toBeCreated.getPoolId() != null && toBeCreated.getState() == Volume.State.Ready)) &&
                    checkIfPoolCanBeReused(vmProfile, plan, avoid, suitableVolumeStoragePools, readyAndReusedVolumes, toBeCreated)) {
                continue;
            }

            if (!isRootAdmin(vmProfile) && !isEnabledForAllocation(plan.getDataCenterId(), plan.getPodId(), plan.getClusterId())) {
                logger.debug("Cannot find new storage pool to deploy volume [{}] of VM [{}] in cluster [{}] because allocation state is disabled. Returning.",
                        toBeCreated, vmProfile, plan.getClusterId());
                suitableVolumeStoragePools.clear();
                break;
            }

            DiskOfferingVO diskOffering = _diskOfferingDao.findById(toBeCreated.getDiskOfferingId());

            DiskProfile diskProfile = new DiskProfile(toBeCreated, diskOffering, vmProfile.getHypervisorType());
            boolean useLocalStorage = false;
            if (vmProfile.getType() != VirtualMachine.Type.User) {
                DataCenterVO zone = _dcDao.findById(plan.getDataCenterId());
                assert (zone != null) : "Invalid zone in deployment plan";
                Boolean useLocalStorageForSystemVM = ConfigurationManagerImpl.SystemVMUseLocalStorage.valueIn(zone.getId());
                if (useLocalStorageForSystemVM != null) {
                    useLocalStorage = useLocalStorageForSystemVM.booleanValue();
                    logger.debug("System VMs will use {} storage for zone {}", useLocalStorage ? "local" : "shared", zone);
                }
            } else {
                useLocalStorage = diskOffering.isUseLocalStorage();
            }
            diskProfile.setUseLocalStorage(useLocalStorage);
            logger.debug("Calling StoragePoolAllocators to find suitable pools to allocate volume [{}] necessary to deploy VM [{}].", toBeCreated, vmProfile);
            boolean foundPotentialPools = tryToFindPotentialPoolsToAlocateVolume(vmProfile, plan, avoid, returnUpTo, suitableVolumeStoragePools, toBeCreated, diskProfile);

            if (avoid.getPoolsToAvoid() != null) {
                poolsToAvoidOutput.addAll(avoid.getPoolsToAvoid());
                avoid.getPoolsToAvoid().retainAll(originalAvoidPoolSet);
            }

            if (!foundPotentialPools) {
                logger.debug("No suitable pools found for volume [{}] used by VM [{}] under cluster: [{}].", toBeCreated, vmProfile, plan.getClusterId());
                // No suitable storage pools found under this cluster for this
                // volume. - remove any suitable pools found for other volumes.
                // All volumes should get suitable pools under this cluster;
                // else we can't use this cluster.
                suitableVolumeStoragePools.clear();
                break;
            }
        }

        HashSet<Long> toRemove = new HashSet<>();
        for (List<StoragePool> lsp : suitableVolumeStoragePools.values()) {
            for (StoragePool sp : lsp) {
                toRemove.add(sp.getId());
            }
        }
        poolsToAvoidOutput.removeAll(toRemove);

        if (avoid.getPoolsToAvoid() != null) {
            avoid.getPoolsToAvoid().addAll(poolsToAvoidOutput);
        }

        if (suitableVolumeStoragePools.isEmpty()) {
            logger.debug("No suitable pools found");
        }

        return new Pair<>(suitableVolumeStoragePools, readyAndReusedVolumes);
    }

    private boolean tryToFindPotentialPoolsToAlocateVolume(VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoid, int returnUpTo,
            Map<Volume, List<StoragePool>> suitableVolumeStoragePools, VolumeVO toBeCreated, DiskProfile diskProfile) {
        for (StoragePoolAllocator allocator : _storagePoolAllocators) {
            logger.debug("Trying to find suitable pools to allocate volume [{}] necessary to deploy VM [{}], using StoragePoolAllocator: [{}].",
                    toBeCreated, vmProfile, allocator.getClass().getSimpleName());

            final List<StoragePool> suitablePools = allocator.allocateToPool(diskProfile, vmProfile, plan, avoid, returnUpTo);
            if (suitablePools != null && !suitablePools.isEmpty()) {
                logger.debug("StoragePoolAllocator [{}] found {} suitable pools to allocate volume [{}] necessary to deploy VM [{}].",
                        allocator.getClass().getSimpleName(), suitablePools.size(), toBeCreated, vmProfile);
                checkForPreferredStoragePool(suitablePools, vmProfile.getVirtualMachine(), suitableVolumeStoragePools, toBeCreated);
                return true;
            }
        }
        return false;
    }

    private boolean checkIfPoolCanBeReused(VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoid,
            Map<Volume, List<StoragePool>> suitableVolumeStoragePools, List<Volume> readyAndReusedVolumes,
            VolumeVO toBeCreated) {
        List<StoragePool> suitablePools = new ArrayList<>();
        StoragePool pool = null;
        if (toBeCreated.getPoolId() != null) {
            pool = (StoragePool)dataStoreMgr.getPrimaryDataStore(toBeCreated.getPoolId());
        } else {
            pool = (StoragePool)dataStoreMgr.getPrimaryDataStore(plan.getPoolId());
        }

        logger.debug("Volume [{}] of VM [{}] has pool [{}] already specified. Checking if this pool can be reused.", toBeCreated, vmProfile, pool);

        if (!pool.isInMaintenance()) {
            if (!avoid.shouldAvoid(pool)) {
                return canReusePool(vmProfile, plan, suitableVolumeStoragePools, readyAndReusedVolumes, toBeCreated, suitablePools, pool);
            } else {
                logger.debug("Pool [{}] of volume [{}] used by VM [{}] is in the avoid set. Need to reallocate a pool for this volume.",
                        pool, toBeCreated, vmProfile);
            }
        } else {
            logger.debug("Pool [{}] of volume [{}] used by VM [{}] is in maintenance. Need to reallocate a pool for this volume.",
                    pool, toBeCreated, vmProfile);
        }
        return false;
    }

    private boolean canReusePool(VirtualMachineProfile vmProfile, DeploymentPlan plan,
            Map<Volume, List<StoragePool>> suitableVolumeStoragePools, List<Volume> readyAndReusedVolumes,
            VolumeVO toBeCreated, List<StoragePool> suitablePools, StoragePool pool) {
        DataStore dataStore = dataStoreMgr.getPrimaryDataStore(pool.getId());

        long exstPoolDcId = pool.getDataCenterId();
        long exstPoolPodId = pool.getPodId() != null ? pool.getPodId() : -1;
        long exstPoolClusterId = pool.getClusterId() != null ? pool.getClusterId() : -1;

        if (plan.getDataCenterId() == exstPoolDcId && ((plan.getPodId() == exstPoolPodId && plan.getClusterId() == exstPoolClusterId) ||
                (dataStore != null && dataStore.getScope() != null && dataStore.getScope().getScopeType() == ScopeType.ZONE))) {
            logger.debug("Pool [{}] of volume [{}] used by VM [{}] fits the specified plan. No need to reallocate a pool for this volume.",
                    pool, toBeCreated, vmProfile);
            suitablePools.add(pool);
            suitableVolumeStoragePools.put(toBeCreated, suitablePools);
            if (!(toBeCreated.getState() == Volume.State.Allocated || toBeCreated.getState() == Volume.State.Creating)) {
                readyAndReusedVolumes.add(toBeCreated);
            }
            return true;
        }

        logger.debug("Pool [{}] of volume [{}] used by VM [{}] does not fit the specified plan. Need to reallocate a pool for this volume.",
                pool, toBeCreated, vmProfile);
        return false;
    }

    private void checkForPreferredStoragePool(List<StoragePool> suitablePools,
                                              VirtualMachine vm,
                                              Map<Volume, List<StoragePool>> suitableVolumeStoragePools,
                                              VolumeVO toBeCreated) {
        List<StoragePool> pools = new ArrayList<>();
        Optional<StoragePool> storagePool = getPreferredStoragePool(suitablePools, vm);
        storagePool.ifPresent(pools::add);

        pools.addAll(suitablePools);
        suitableVolumeStoragePools.put(toBeCreated, pools);
    }

    private Optional<StoragePool> getMatchingStoragePool(String preferredPoolId, List<StoragePool> storagePools) {
        if (preferredPoolId == null) {
            return Optional.empty();
        }
        return storagePools.stream()
                .filter(pool -> pool.getUuid().equalsIgnoreCase(preferredPoolId))
                .findFirst();
    }

    private Optional<StoragePool> getPreferredStoragePool(List<StoragePool> poolList, VirtualMachine vm) {
        String accountStoragePoolUuid = StorageManager.PreferredStoragePool.valueIn(vm.getAccountId());
        Optional<StoragePool> storagePool = getMatchingStoragePool(accountStoragePoolUuid, poolList);

        if (storagePool.isPresent()) {
            logger.debug("A storage pool is specified for this account, so we will use this storage pool for allocation: {}", storagePool.get());
        } else {
            String globalStoragePoolUuid = StorageManager.PreferredStoragePool.value();
            storagePool = getMatchingStoragePool(globalStoragePoolUuid, poolList);
            storagePool.ifPresent(pool -> logger.debug("A storage pool is specified in global setting, so we will use this storage pool for allocation: {}", pool));
        }
        return storagePool;
    }

    private boolean isEnabledForAllocation(long zoneId, Long podId, Long clusterId) {
        // Check if the zone exists in the system
        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone != null && Grouping.AllocationState.Disabled == zone.getAllocationState()) {
            logger.info("Zone is currently disabled, cannot allocate to this zone: {}", zone);
            return false;
        }

        Pod pod = _podDao.findById(podId);
        if (pod != null && Grouping.AllocationState.Disabled == pod.getAllocationState()) {
            logger.info("Pod is currently disabled, cannot allocate to this pod: {}", pod);
            return false;
        }

        Cluster cluster = _clusterDao.findById(clusterId);
        if (cluster != null && Grouping.AllocationState.Disabled == cluster.getAllocationState()) {
            logger.info("Cluster is currently disabled, cannot allocate to this cluster: {}", cluster);
            return false;
        }

        return true;
    }

    private boolean isRootAdmin(VirtualMachineProfile vmProfile) {
        if (vmProfile != null) {
            if (vmProfile.getOwner() != null) {
                return _accountMgr.isRootAdmin(vmProfile.getOwner().getId());
            } else {
                return false;
            }
        }
        return false;
    }

    @DB
    @Override
    public String finalizeReservation(final DeployDestination plannedDestination, final VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoids, final DeploymentPlanner planner)
            throws InsufficientServerCapacityException, AffinityConflictException {

        final VirtualMachine vm = vmProfile.getVirtualMachine();
        final long vmGroupCount = _affinityGroupVMMapDao.countAffinityGroupsForVm(vm.getId());

        return Transaction.execute(new TransactionCallback<>() {
            @Override
            public String doInTransaction(TransactionStatus status) {
                boolean saveReservation = true;

                if (vmGroupCount > 0) {
                    List<Long> groupIds = _affinityGroupVMMapDao.listAffinityGroupIdsByVmId(vm.getId());
                    SearchCriteria<AffinityGroupVO> criteria = _affinityGroupDao.createSearchCriteria();
                    criteria.addAnd("id", SearchCriteria.Op.IN, groupIds.toArray(new Object[groupIds.size()]));
                    _affinityGroupDao.lockRows(criteria, null, true);

                    for (AffinityGroupProcessor processor : _affinityProcessors) {
                        if (!processor.check(vmProfile, plannedDestination)) {
                            saveReservation = false;
                            break;
                        }
                    }
                }

                if (saveReservation) {
                    VMReservationVO vmReservation =
                            new VMReservationVO(vm.getId(), plannedDestination.getDataCenter().getId(), plannedDestination.getPod().getId(), plannedDestination.getCluster()
                                    .getId(), plannedDestination.getHost().getId());
                    if (planner != null) {
                        vmReservation.setDeploymentPlanner(planner.getName());
                    }
                    Map<Long, Long> volumeReservationMap = new HashMap<>();

                    if (!DEPLOYMENT_PLANNING_SKIP_HYPERVISORS.contains(vm.getHypervisorType())) {
                        for (Volume vo : plannedDestination.getStorageForDisks().keySet()) {
                            volumeReservationMap.put(vo.getId(), plannedDestination.getStorageForDisks().get(vo).getId());
                        }
                        vmReservation.setVolumeReservation(volumeReservationMap);
                    }
                    _reservationDao.persist(vmReservation);
                    return vmReservation.getUuid();
                }

                return null;
            }
        });
    }

    @Override
    public boolean preStateTransitionEvent(State oldState, Event event, State newState, VirtualMachine vo, boolean status, Object opaque) {
        return true;
    }

    @Override
    public boolean postStateTransitionEvent(StateMachine2.Transition<State, Event> transition, VirtualMachine vo, boolean status, Object opaque) {
      if (!status) {
        return false;
      }
      State oldState = transition.getCurrentState();
      State newState = transition.getToState();
      if ((oldState == State.Starting) && (newState != State.Starting)) {
        // cleanup all VM reservation entries
        SearchCriteria<VMReservationVO> sc = _reservationDao.createSearchCriteria();
        sc.addAnd("vmId", SearchCriteria.Op.EQ, vo.getId());
        _reservationDao.expunge(sc);
      }
      return true;
    }

    public static String logDeploymentWithoutException(VirtualMachine vm, DeploymentPlan plan, ExcludeList avoids, DeploymentPlanner planner) {
        return LogUtils.logGsonWithoutException("Trying to deploy VM [%s] and details: Plan [%s]; avoid list [%s] and planner: [%s].", vm, plan, avoids, planner != null ? planner.getName() : null);
    }
    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {allowRouterOnDisabledResource, allowAdminVmOnDisabledResource};
    }

    @Override
    public String getConfigComponentName() {
        return DeploymentPlanningManager.class.getSimpleName();
    }
}
