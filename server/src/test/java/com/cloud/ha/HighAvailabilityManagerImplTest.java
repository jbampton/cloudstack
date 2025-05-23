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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.managed.context.ManagedContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.alert.AlertManager;
import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.ha.HighAvailabilityManager.Step;
import com.cloud.ha.HighAvailabilityManager.WorkType;
import com.cloud.ha.dao.HighAvailabilityDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.VpcVirtualNetworkApplianceService;
import com.cloud.resource.ResourceManager;
import com.cloud.server.ManagementServer;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.StorageManager;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.user.AccountManager;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class HighAvailabilityManagerImplTest {
    protected Logger logger = LogManager.getLogger(getClass());
    @Mock
    HighAvailabilityDao _haDao;
    @Mock
    VMInstanceDao _instanceDao;
    @Mock
    HostDao _hostDao;
    @Mock
    DataCenterDao _dcDao;
    @Mock
    HostPodDao _podDao;
    @Mock
    ClusterDetailsDao _clusterDetailsDao;
    @Mock
    ServiceOfferingDao _serviceOfferingDao;
    @Mock
    ManagedContext _managedContext;
    @Mock
    AgentManager _agentMgr;
    @Mock
    AlertManager _alertMgr;
    @Mock
    StorageManager _storageMgr;
    @Mock
    GuestOSDao _guestOSDao;
    @Mock
    GuestOSCategoryDao _guestOSCategoryDao;
    @Mock
    VirtualMachineManager _itMgr;
    @Mock
    AccountManager _accountMgr;
    @Mock
    ResourceManager _resourceMgr;
    @Mock
    ManagementServer _msServer;
    @Mock
    ConfigurationDao _configDao;
    @Mock
    VolumeOrchestrationService volumeMgr;
    @Mock
    ConsoleProxyManager consoleProxyManager;
    @Mock
    SecondaryStorageVmManager secondaryStorageVmManager;
    @Mock
    HostVO hostVO;
    @Mock
    VolumeDao volumeDao;
    @Mock
    DataStoreProviderManager dataStoreProviderMgr;
    @Mock
    VpcVirtualNetworkApplianceService routerService;
    @Mock
    UserVmManager userVmManager;

    @Mock
    private HaWorkVO mockWork;

    HighAvailabilityManagerImpl highAvailabilityManager;
    HighAvailabilityManagerImpl highAvailabilityManagerSpy;
    static Method processWorkMethod = null;

    @BeforeClass
    public static void initOnce() {
        try {
            processWorkMethod = HighAvailabilityManagerImpl.class.getDeclaredMethod("processWork", HaWorkVO.class);
            processWorkMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
        }
    }

    @Before
    public void setup() throws IllegalArgumentException,
            IllegalAccessException, NoSuchFieldException, SecurityException {
        highAvailabilityManager = new HighAvailabilityManagerImpl();
        for (Field injectField : HighAvailabilityManagerImpl.class.getDeclaredFields()) {
            if (injectField.isAnnotationPresent(Inject.class)) {
                injectField.setAccessible(true);
                injectField.set(highAvailabilityManager, this.getClass().getDeclaredField(injectField.getName()).get(this));
            } else if (injectField.getName().equals("_workers")) {
                injectField.setAccessible(true);
                for (Class<?> clz : HighAvailabilityManagerImpl.class.getDeclaredClasses()) {
                    if (clz.getName().equals("com.cloud.ha.HighAvailabilityManagerImpl$WorkerThread")) {
                        Object obj = Array.newInstance(clz, 0);
                        injectField.set(highAvailabilityManager, obj);
                    }
                }
            } else if (injectField.getName().equals("_maxRetries")) {
                injectField.setAccessible(true);
                injectField.set(highAvailabilityManager, 5);
            }
        }
        highAvailabilityManagerSpy = Mockito.spy(highAvailabilityManager);
    }

    @Test
    public void scheduleRestartForVmsOnHost() {
        Mockito.when(hostVO.getType()).thenReturn(Host.Type.Routing);
        Mockito.when(hostVO.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(hostVO.getDataCenterId()).thenReturn(1L);
        Mockito.lenient().when(_instanceDao.listByHostId(42l)).thenReturn(Arrays.asList(Mockito.mock(VMInstanceVO.class)));
        Mockito.when(_podDao.findById(Mockito.anyLong())).thenReturn(Mockito.mock(HostPodVO.class));
        Mockito.when(_dcDao.findById(Mockito.anyLong())).thenReturn(Mockito.mock(DataCenterVO.class));

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(true);

        highAvailabilityManager.scheduleRestartForVmsOnHost(hostVO, true, HighAvailabilityManager.ReasonType.HostDown);
    }

    @Test
    public void scheduleRestartForVmsOnHostNotSupported() {
        Mockito.when(hostVO.getType()).thenReturn(Host.Type.Routing);
        Mockito.when(hostVO.getHypervisorType()).thenReturn(HypervisorType.VMware);

        highAvailabilityManager.scheduleRestartForVmsOnHost(hostVO, true, HighAvailabilityManager.ReasonType.HostDown);
    }

    @Test
    public void scheduleRestartForVmsOnHostHADisabled() {
        Mockito.when(hostVO.getType()).thenReturn(Host.Type.Routing);
        Mockito.when(hostVO.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(hostVO.getDataCenterId()).thenReturn(1L);

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(false);

        highAvailabilityManager.scheduleRestartForVmsOnHost(hostVO, true, HighAvailabilityManager.ReasonType.HostDown);
    }

    @Test
    public void scheduleRestartForVmsOnHostNonEmptyVMList() {
        Mockito.when(hostVO.getId()).thenReturn(1l);
        Mockito.when(hostVO.getType()).thenReturn(Host.Type.Routing);
        Mockito.when(hostVO.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        Mockito.when(hostVO.getDataCenterId()).thenReturn(1L);
        List<VMInstanceVO> vms = new ArrayList<VMInstanceVO>();
        VMInstanceVO vm1 = Mockito.mock(VMInstanceVO.class);
        Mockito.lenient().when(vm1.getHostId()).thenReturn(1l);
        Mockito.when(vm1.getType()).thenReturn(VirtualMachine.Type.User);
        Mockito.when(vm1.isHaEnabled()).thenReturn(true);
        vms.add(vm1);
        VMInstanceVO vm2 = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm2.getHostId()).thenReturn(1l);
        Mockito.when(vm2.getType()).thenReturn(VirtualMachine.Type.DomainRouter);
        Mockito.when(vm2.isHaEnabled()).thenReturn(true);
        Mockito.when(vm2.getDataCenterId()).thenReturn(1L);
        vms.add(vm2);
        Mockito.when(_instanceDao.listByHostId(Mockito.anyLong())).thenReturn(vms);
        Mockito.when(_instanceDao.findByUuid(vm1.getUuid())).thenReturn(vm1);
        Mockito.when(_instanceDao.findByUuid(vm2.getUuid())).thenReturn(vm2);
        Mockito.when(_podDao.findById(Mockito.anyLong())).thenReturn(Mockito.mock(HostPodVO.class));
        Mockito.when(_dcDao.findById(Mockito.anyLong())).thenReturn(Mockito.mock(DataCenterVO.class));
        Mockito.when(_haDao.findPreviousHA(Mockito.anyLong())).thenReturn(Arrays.asList(Mockito.mock(HaWorkVO.class)));
        Mockito.when(_haDao.persist((HaWorkVO)Mockito.any())).thenReturn(Mockito.mock(HaWorkVO.class));
        Mockito.when(_serviceOfferingDao.findById(vm1.getServiceOfferingId())).thenReturn(Mockito.mock(ServiceOfferingVO.class));

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(true);

        highAvailabilityManager.scheduleRestartForVmsOnHost(hostVO, true, HighAvailabilityManager.ReasonType.HostDown);
    }

    @Test
    public void scheduleRestartHADisabled() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(false);

        highAvailabilityManager.scheduleRestart(vm, true);
    }

    @Test
    public void scheduleRestartHostNotSupported() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.when(vm.getHostId()).thenReturn(1L);
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);

        highAvailabilityManager.scheduleRestart(vm, true);
    }

    @Test
    public void scheduleStop() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(_haDao.hasBeenScheduled(vm.getId(), WorkType.Stop)).thenReturn(false);
        Mockito.when(_haDao.persist((HaWorkVO)Mockito.any())).thenReturn(Mockito.mock(HaWorkVO.class));

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(true);

        assertTrue(highAvailabilityManager.scheduleStop(vm, 1L, WorkType.Stop));
    }

    @Test
    public void scheduleStopHADisabled() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.when(_haDao.hasBeenScheduled(vm.getId(), WorkType.Stop)).thenReturn(false);

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(false);

        assertFalse(highAvailabilityManager.scheduleStop(vm, 1L, WorkType.Stop));
    }

    @Test
    public void scheduleMigration() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(vm.getHostId()).thenReturn(1L);
        Mockito.when(_haDao.persist((HaWorkVO)Mockito.any())).thenReturn(Mockito.mock(HaWorkVO.class));

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(true);

        assertTrue(highAvailabilityManager.scheduleMigration(vm));
    }

    @Test
    public void scheduleMigrationHADisabled() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getHostId()).thenReturn(1L);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(false);

        assertFalse(highAvailabilityManager.scheduleMigration(vm));
    }

    @Test
    public void scheduleDestroy() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(_haDao.persist((HaWorkVO)Mockito.any())).thenReturn(Mockito.mock(HaWorkVO.class));

        assertTrue(highAvailabilityManager.scheduleDestroy(vm, 1L, HighAvailabilityManager.ReasonType.HostMaintenance));
    }

    @Test
    public void scheduleDestroyHADisabled() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(false);

        assertFalse(highAvailabilityManager.scheduleDestroy(vm, 1L, HighAvailabilityManager.ReasonType.HostMaintenance));
    }

    @Test
    public void investigateHostStatusSuccess() {
        Mockito.when(_hostDao.findById(Mockito.anyLong())).thenReturn(hostVO);
        Mockito.when(hostVO.getDataCenterId()).thenReturn(1L);
        // Set the list of investigators, CheckOnAgentInvestigator suffices for now
        Investigator investigator = Mockito.mock(CheckOnAgentInvestigator.class);
        List<Investigator> investigators = new ArrayList<Investigator>();
        investigators.add(investigator);
        highAvailabilityManager.setInvestigators(investigators);
        // Mock isAgentAlive to return host status as Down
        Mockito.when(investigator.isAgentAlive(hostVO)).thenReturn(Status.Down);

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(true);

        assertTrue(highAvailabilityManager.investigate(1l) == Status.Down);
    }

    @Test
    public void investigateHostStatusFailure() {
        Mockito.when(_hostDao.findById(Mockito.anyLong())).thenReturn(hostVO);
        Mockito.when(hostVO.getDataCenterId()).thenReturn(1L);
        // Set the list of investigators, CheckOnAgentInvestigator suffices for now
        // Also no need to mock isAgentAlive() as actual implementation returns null
        Investigator investigator = Mockito.mock(CheckOnAgentInvestigator.class);
        List<Investigator> investigators = new ArrayList<Investigator>();
        investigators.add(investigator);
        highAvailabilityManager.setInvestigators(investigators);

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(true);

        assertNull(highAvailabilityManager.investigate(1l));
    }

    @Test
    public void investigateHostStatusHADisabled() {
        Mockito.when(_hostDao.findById(Mockito.anyLong())).thenReturn(hostVO);
        Mockito.when(hostVO.getDataCenterId()).thenReturn(1L);

        ConfigKey<Boolean> haEnabled = Mockito.mock(ConfigKey.class);
        highAvailabilityManager.VmHaEnabled = haEnabled;
        Mockito.when(highAvailabilityManager.VmHaEnabled.valueIn(1L)).thenReturn(false);

        assertTrue(highAvailabilityManager.investigate(1L) == Status.Alert);
    }

    private void processWorkWithRetryCount(int count, Step expectedStep) {
        assertNotNull(processWorkMethod);
        HaWorkVO work = new HaWorkVO(1l, VirtualMachine.Type.User, WorkType.Migration, Step.Scheduled, 1l, VirtualMachine.State.Running, count, 12345678l, null);
        Mockito.doReturn(12345678l).when(highAvailabilityManagerSpy).migrate(work);
        try {
            processWorkMethod.invoke(highAvailabilityManagerSpy, work);
        } catch (IllegalAccessException e) {
            logger.info("[ignored] expected IllegalAccessException caught: " + e.getLocalizedMessage());
        } catch (IllegalArgumentException e) {
            logger.info("[ignored] expected IllegalArgumentException caught: " + e.getLocalizedMessage());
        } catch (InvocationTargetException e) {
            logger.info("[ignored] expected InvocationTargetException caught: " + e.getLocalizedMessage());
        }
        assertTrue(work.getStep() == expectedStep);
    }

    @Test
    public void processWorkWithRetryCountExceeded() {
        processWorkWithRetryCount(5, Step.Done); // max retry count is 5
    }

    @Test
    public void processWorkWithRetryCountNotExceeded() {
        processWorkWithRetryCount(3, Step.Scheduled);
    }

    @Test
    public void testCheckAndCancelWorkIfNeeded_Success() {
        Mockito.when(mockWork.getStep()).thenReturn(Step.Investigating);
        Mockito.when(mockWork.getReasonType()).thenReturn(HighAvailabilityManager.ReasonType.HostMaintenance);
        Mockito.when(mockWork.getHostId()).thenReturn(1L);
        Mockito.doReturn(Status.Up).when(highAvailabilityManagerSpy).investigate(1L);
        Mockito.doNothing().when(mockWork).setStep(Step.Cancelled);
        boolean result = highAvailabilityManagerSpy.checkAndCancelWorkIfNeeded(mockWork);
        assertTrue(result);
        Mockito.verify(mockWork).setStep(Step.Cancelled);
    }

    @Test
    public void testCheckAndCancelWorkIfNeeded_StepNotInvestigating() {
        Mockito.when(mockWork.getStep()).thenReturn(Step.Cancelled);
        boolean result = highAvailabilityManagerSpy.checkAndCancelWorkIfNeeded(mockWork);
        assertFalse(result);
        Mockito.verify(mockWork, Mockito.never()).setStep(Mockito.any());
    }

    private void runInvalidReasonCheckAndCancelWorkIfNeeded(HighAvailabilityManager.ReasonType reasonType) {
        Mockito.when(mockWork.getStep()).thenReturn(Step.Investigating);
        Mockito.when(mockWork.getReasonType()).thenReturn(reasonType);
        boolean result = highAvailabilityManagerSpy.checkAndCancelWorkIfNeeded(mockWork);
        assertFalse(result);
        Mockito.verify(mockWork, Mockito.never()).setStep(Mockito.any());
    }

    @Test
    public void testCheckAndCancelWorkIfNeeded_InvalidReasonType() {
        runInvalidReasonCheckAndCancelWorkIfNeeded(HighAvailabilityManager.ReasonType.Unknown);
    }

    @Test
    public void testCheckAndCancelWorkIfNeeded_NullReasonType() {
        runInvalidReasonCheckAndCancelWorkIfNeeded(null);
    }

    @Test
    public void testCheckAndCancelWorkIfNeeded_HostStatusNotUp() {
        Mockito.when(mockWork.getStep()).thenReturn(Step.Investigating);
        Mockito.when(mockWork.getReasonType()).thenReturn(HighAvailabilityManager.ReasonType.HostDown);
        Mockito.when(mockWork.getHostId()).thenReturn(1L);
        Mockito.doReturn(Status.Down).when(highAvailabilityManagerSpy).investigate(1L);
        boolean result = highAvailabilityManagerSpy.checkAndCancelWorkIfNeeded(mockWork);
        assertFalse(result);
        Mockito.verify(mockWork, Mockito.never()).setStep(Mockito.any());
    }
}
