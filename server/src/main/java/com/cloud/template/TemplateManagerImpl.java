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
package com.cloud.template;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.BaseListTemplateOrIsoPermissionsCmd;
import org.apache.cloudstack.api.BaseUpdateTemplateOrIsoCmd;
import org.apache.cloudstack.api.BaseUpdateTemplateOrIsoPermissionsCmd;
import org.apache.cloudstack.api.command.user.iso.DeleteIsoCmd;
import org.apache.cloudstack.api.command.user.iso.ExtractIsoCmd;
import org.apache.cloudstack.api.command.user.iso.GetUploadParamsForIsoCmd;
import org.apache.cloudstack.api.command.user.iso.ListIsoPermissionsCmd;
import org.apache.cloudstack.api.command.user.iso.RegisterIsoCmd;
import org.apache.cloudstack.api.command.user.iso.UpdateIsoCmd;
import org.apache.cloudstack.api.command.user.iso.UpdateIsoPermissionsCmd;
import org.apache.cloudstack.api.command.user.template.CopyTemplateCmd;
import org.apache.cloudstack.api.command.user.template.CreateTemplateCmd;
import org.apache.cloudstack.api.command.user.template.DeleteTemplateCmd;
import org.apache.cloudstack.api.command.user.template.ExtractTemplateCmd;
import org.apache.cloudstack.api.command.user.template.GetUploadParamsForTemplateCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatePermissionsCmd;
import org.apache.cloudstack.api.command.user.template.RegisterTemplateCmd;
import org.apache.cloudstack.api.command.user.template.RegisterVnfTemplateCmd;
import org.apache.cloudstack.api.command.user.template.UpdateTemplateCmd;
import org.apache.cloudstack.api.command.user.template.UpdateTemplatePermissionsCmd;
import org.apache.cloudstack.api.command.user.template.UpdateVnfTemplateCmd;
import org.apache.cloudstack.api.command.user.userdata.LinkUserDataToTemplateCmd;
import org.apache.cloudstack.api.response.GetUploadParamsResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageCacheManager;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageStrategyFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateService;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateService.TemplateApiResult;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.secstorage.dao.SecondaryStorageHeuristicDao;
import org.apache.cloudstack.secstorage.heuristics.HeuristicType;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.command.TemplateOrVolumePostUploadCommand;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.heuristics.HeuristicRuleHelper;
import org.apache.cloudstack.storage.image.datastore.ImageStoreEntity;
import org.apache.cloudstack.storage.template.VnfTemplateManager;
import org.apache.cloudstack.storage.template.VnfTemplateUtils;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.utils.imagestore.ImageStoreUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.ComputeChecksumCommand;
import com.cloud.agent.api.storage.DestroyCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DatadiskTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.configuration.Config;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.cpu.CPU;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.event.UsageEventVO;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.ImageStoreUploadMonitorImpl;
import com.cloud.storage.LaunchPermissionVO;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.TemplateType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.TemplateProfile;
import com.cloud.storage.Upload;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.LaunchPermissionDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateDetailsDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.template.TemplateAdapter.TemplateAdapterType;
import com.cloud.template.VirtualMachineTemplate.BootloaderType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserData;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.DateUtil;
import com.cloud.utils.EncryptionUtil;
import com.cloud.utils.EnumUtils;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.common.base.Joiner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class TemplateManagerImpl extends ManagerBase implements TemplateManager, TemplateApiService, Configurable {

    @Inject
    private VMTemplateDao _tmpltDao;
    @Inject
    private TemplateDataStoreDao _tmplStoreDao;
    @Inject
    private VMTemplatePoolDao _tmpltPoolDao;
    @Inject
    private VMTemplateZoneDao _tmpltZoneDao;
    @Inject
    private VMInstanceDao _vmInstanceDao;
    @Inject
    private PrimaryDataStoreDao _poolDao;
    @Inject
    private StoragePoolHostDao _poolHostDao;
    @Inject
    private AccountDao _accountDao;
    @Inject
    private AgentManager _agentMgr;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private HostDao _hostDao;
    @Inject
    private DataCenterDao _dcDao;
    @Inject
    private UserVmDao _userVmDao;
    @Inject
    private VolumeDao _volumeDao;
    @Inject
    private SnapshotDao _snapshotDao;
    @Inject
    private ConfigurationDao _configDao;
    @Inject
    private DomainDao _domainDao;
    @Inject
    private GuestOSDao _guestOSDao;
    @Inject
    private StorageManager _storageMgr;
    @Inject
    private UsageEventDao _usageEventDao;
    @Inject
    private AccountService _accountService;
    @Inject
    private ResourceLimitService _resourceLimitMgr;
    @Inject
    private LaunchPermissionDao _launchPermissionDao;
    @Inject
    private ProjectManager _projectMgr;
    @Inject
    private VolumeDataFactory _volFactory;
    @Inject
    private TemplateDataFactory _tmplFactory;
    @Inject
    private SnapshotDataFactory _snapshotFactory;
    @Inject
    StorageStrategyFactory _storageStrategyFactory;
    @Inject
    private TemplateService _tmpltSvr;
    @Inject
    private DataStoreManager _dataStoreMgr;
    @Inject
    private VolumeOrchestrationService _volumeMgr;
    @Inject
    private EndPointSelector _epSelector;
    @Inject
    private UserVmJoinDao _userVmJoinDao;
    @Inject
    private SnapshotDataStoreDao _snapshotStoreDao;
    @Inject
    private ImageStoreDao _imgStoreDao;
    @Inject
    MessageBus _messageBus;
    @Inject
    private VMTemplateDetailsDao _tmpltDetailsDao;
    @Inject
    private HypervisorGuruManager _hvGuruMgr;

    private List<TemplateAdapter> _adapters;

    ExecutorService _preloadExecutor;

    @Inject
    private StorageCacheManager cacheMgr;
    @Inject
    private EndPointSelector selector;

    @Inject
    protected SnapshotHelper snapshotHelper;
    @Inject
    VnfTemplateManager vnfTemplateManager;

    @Inject
    private SecondaryStorageHeuristicDao secondaryStorageHeuristicDao;

    @Inject
    private HeuristicRuleHelper heuristicRuleHelper;

    private TemplateAdapter getAdapter(HypervisorType type) {
        TemplateAdapter adapter = null;
        if (type == HypervisorType.BareMetal) {
            adapter = AdapterBase.getAdapterByName(_adapters, TemplateAdapterType.BareMetal.getName());
        } else if (type == HypervisorType.External) {
            adapter = AdapterBase.getAdapterByName(_adapters, TemplateAdapterType.External.getName());
        } else {
            // Get template adapter according to hypervisor
            adapter = AdapterBase.getAdapterByName(_adapters, type.name());
            // Otherwise, default to generic hypervisor template adapter
            if (adapter == null) {
                adapter = AdapterBase.getAdapterByName(_adapters, TemplateAdapterType.Hypervisor.getName());
            }
        }

        if (adapter == null) {
            throw new CloudRuntimeException("Cannot find template adapter for " + type.toString());
        }

        return adapter;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ISO_CREATE, eventDescription = "creating iso")
    public VirtualMachineTemplate registerIso(RegisterIsoCmd cmd) throws ResourceAllocationException {
        TemplateAdapter adapter = getAdapter(HypervisorType.None);
        TemplateProfile profile = adapter.prepare(cmd);
        VMTemplateVO template = adapter.create(profile);

        if (template != null) {
            CallContext.current().putContextParameter(VirtualMachineTemplate.class, template.getUuid());
            return template;
        } else {
            throw new CloudRuntimeException("Failed to create ISO");
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TEMPLATE_CREATE, eventDescription = "creating template")
    public VirtualMachineTemplate registerTemplate(RegisterTemplateCmd cmd) throws URISyntaxException, ResourceAllocationException {
        Account account = CallContext.current().getCallingAccount();
        if (cmd.getTemplateTag() != null) {
            if (!_accountService.isRootAdmin(account.getId())) {
                throw new PermissionDeniedException("Parameter templatetag can only be specified by a Root Admin, permission denied");
            }
        }
        if (cmd.isRoutingType() != null) {
            if (!_accountService.isRootAdmin(account.getId())) {
                throw new PermissionDeniedException("Parameter isrouting can only be specified by a Root Admin, permission denied");
            }
        }

        TemplateAdapter adapter = getAdapter(HypervisorType.getType(cmd.getHypervisor()));
        TemplateProfile profile = adapter.prepare(cmd);
        VMTemplateVO template = adapter.create(profile);

        if (template != null) {
            CallContext.current().putContextParameter(VirtualMachineTemplate.class, template.getUuid());
            if (cmd instanceof RegisterVnfTemplateCmd) {
                vnfTemplateManager.persistVnfTemplate(template.getId(), (RegisterVnfTemplateCmd) cmd);
            }
            return template;
        } else {
            throw new CloudRuntimeException("Failed to create a template");
        }
    }

    /**
     * Internal register template or ISO method - post local upload
     * @param adapter
     * @param profile
     */
    private GetUploadParamsResponse registerPostUploadInternal(TemplateAdapter adapter,
                                                               TemplateProfile profile) throws MalformedURLException {

        List<TemplateOrVolumePostUploadCommand> payload = adapter.createTemplateForPostUpload(profile);

        if(CollectionUtils.isNotEmpty(payload)) {
            GetUploadParamsResponse response = new GetUploadParamsResponse();

            /*
             * There can be one or more commands depending on the number of secondary stores the template needs to go to. Taking the first one to do the url upload. The
             * template will be propagated to the rest through copy by management server commands.
             */
            TemplateOrVolumePostUploadCommand firstCommand = payload.get(0);

            String ssvmUrlDomain = _configDao.getValue(Config.SecStorageSecureCopyCert.key());
            String protocol = VolumeApiService.UseHttpsToUpload.value() ? "https" : "http";

            String url = ImageStoreUtil.generatePostUploadUrl(ssvmUrlDomain, firstCommand.getRemoteEndPoint(), firstCommand.getEntityUUID(), protocol);
            response.setPostURL(new URL(url));

            // set the post url, this is used in the monitoring thread to determine the SSVM
            TemplateDataStoreVO templateStore = _tmplStoreDao.findByTemplate(firstCommand.getEntityId(), DataStoreRole.getRole(firstCommand.getDataToRole()));
            if (templateStore != null) {
                templateStore.setExtractUrl(url);
                _tmplStoreDao.persist(templateStore);
            }

            response.setId(UUID.fromString(firstCommand.getEntityUUID()));

            int timeout = ImageStoreUploadMonitorImpl.getUploadOperationTimeout();
            DateTime currentDateTime = new DateTime(DateTimeZone.UTC);
            String expires = currentDateTime.plusMinutes(timeout).toString();
            response.setTimeout(expires);

            String key = _configDao.getValue(Config.SSVMPSK.key());
            /*
             * encoded metadata using the post upload config ssh key
             */
            Gson gson = new GsonBuilder().create();
            String metadata = EncryptionUtil.encodeData(gson.toJson(firstCommand), key);
            response.setMetadata(metadata);

            /*
             * signature calculated on the url, expiry, metadata.
             */
            response.setSignature(EncryptionUtil.generateSignature(metadata + url + expires, key));

            return response;
        } else {
            throw new CloudRuntimeException("Unable to register template.");
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ISO_CREATE, eventDescription = "creating post upload iso")
    public GetUploadParamsResponse registerIsoForPostUpload(GetUploadParamsForIsoCmd cmd) throws ResourceAllocationException, MalformedURLException {
        TemplateAdapter adapter = getAdapter(HypervisorType.None);
        TemplateProfile profile = adapter.prepare(cmd);
        return registerPostUploadInternal(adapter, profile);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TEMPLATE_CREATE, eventDescription = "creating post upload template")
    public GetUploadParamsResponse registerTemplateForPostUpload(GetUploadParamsForTemplateCmd cmd) throws ResourceAllocationException, MalformedURLException {
        TemplateAdapter adapter = getAdapter(HypervisorType.getType(cmd.getHypervisor()));
        TemplateProfile profile = adapter.prepare(cmd);
        return registerPostUploadInternal(adapter, profile);
    }

    @Override
    public DataStore getImageStore(String storeUuid, Long zoneId, VolumeVO volume) {
        DataStore imageStore = null;
        if (storeUuid != null) {
            imageStore = _dataStoreMgr.getDataStore(storeUuid, DataStoreRole.Image);
        } else {
            imageStore = heuristicRuleHelper.getImageStoreIfThereIsHeuristicRule(zoneId, HeuristicType.VOLUME, volume);
            if (imageStore == null) {
                imageStore = _dataStoreMgr.getImageStoreWithFreeCapacity(zoneId);
            }
        }

        if (imageStore == null) {
            throw new CloudRuntimeException(String.format("Cannot find an image store for zone [%s].", zoneId));
        }

        return imageStore;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ISO_EXTRACT, eventDescription = "extracting ISO", async = true)
    public String extract(ExtractIsoCmd cmd) {
        Account account = CallContext.current().getCallingAccount();
        Long templateId = cmd.getId();
        Long zoneId = cmd.getZoneId();
        String url = cmd.getUrl();
        String mode = cmd.getMode();
        Long eventId = cmd.getStartEventId();

        String extractUrl = extract(account, templateId, url, zoneId, mode, eventId, true);
        CallContext.current().setEventDetails(String.format("Download URL: %s, ISO ID: %s", extractUrl, _tmpltDao.findById(templateId).getUuid()));
        return extractUrl;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TEMPLATE_EXTRACT, eventDescription = "extracting template", async = true)
    public String extract(ExtractTemplateCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long templateId = cmd.getId();
        Long zoneId = cmd.getZoneId();
        String url = cmd.getUrl();
        String mode = cmd.getMode();
        Long eventId = cmd.getStartEventId();

        VirtualMachineTemplate template = _tmpltDao.findById(templateId);
        if (template == null) {
            throw new InvalidParameterValueException("unable to find template with id " + templateId);
        }

        String extractUrl = extract(caller, templateId, url, zoneId, mode, eventId, false);
        CallContext.current().setEventDetails(String.format("Download URL: %s, template ID: %s", extractUrl, template.getUuid()));
        return extractUrl;
    }

    @Override
    public VirtualMachineTemplate prepareTemplate(long templateId, long zoneId, Long storageId) {

        VMTemplateVO vmTemplate = _tmpltDao.findById(templateId);
        if (vmTemplate == null) {
            throw new InvalidParameterValueException("Unable to find template id=" + templateId);
        }

        _accountMgr.checkAccess(CallContext.current().getCallingAccount(), AccessType.OperateEntry, true, vmTemplate);

        if (storageId != null) {
            StoragePoolVO pool = _poolDao.findById(storageId);
            if (pool != null) {
                if (pool.getStatus() == StoragePoolStatus.Up && pool.getDataCenterId() == zoneId) {
                    prepareTemplateInOneStoragePool(vmTemplate, pool);
                } else {
                    logger.warn("Skip loading template {} into primary storage {} as " +
                            "either the pool zone {} is different from the requested zone {} or " +
                            "the pool is currently not available.",
                            vmTemplate::toString, pool::toString, () -> _dcDao.findById(pool.getDataCenterId()), () -> _dcDao.findById(zoneId));
                }
            }
        } else {
            prepareTemplateInAllStoragePools(vmTemplate, zoneId);
        }
        return vmTemplate;
    }

    private String extract(Account caller, Long templateId, String url, Long zoneId, String mode, Long eventId, boolean isISO) {
        String desc = Upload.Type.TEMPLATE.toString();
        if (isISO) {
            desc = Upload.Type.ISO.toString();
        }
        if (!_accountMgr.isRootAdmin(caller.getId()) && ApiDBUtils.isExtractionDisabled()) {
            throw new PermissionDeniedException("Extraction has been disabled by admin");
        }

        VMTemplateVO template = _tmpltDao.findById(templateId);
        if (template == null || template.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find " + desc + " with id " + templateId);
        }

        if (template.getTemplateType() == Storage.TemplateType.PERHOST) {
            throw new InvalidParameterValueException("Unable to extract the " + desc + " " + template.getName() + " as it resides on host and not on SSVM");
        }

        if (isISO) {
            if (template.getFormat() != ImageFormat.ISO) {
                throw new InvalidParameterValueException("Unsupported format, could not extract the ISO");
            }
        } else {
            if (template.getFormat() == ImageFormat.ISO) {
                throw new InvalidParameterValueException("Unsupported format, could not extract the template");
            }
        }

        if (zoneId != null && _dcDao.findById(zoneId) == null) {
            throw new IllegalArgumentException("Please specify a valid zone.");
        }

        if (!_accountMgr.isRootAdmin(caller.getId()) && !template.isExtractable()) {
            throw new InvalidParameterValueException(String.format("Unable to extract template %s as it's not extractable", template));
        }

        _accountMgr.checkAccess(caller, AccessType.OperateEntry, true, template);

        List<DataStore> ssStores = _dataStoreMgr.getImageStoresByScope(new ZoneScope(null));

        TemplateDataStoreVO tmpltStoreRef = null;
        ImageStoreEntity tmpltStore = null;
        if (ssStores != null) {
            for (DataStore store : ssStores) {
                tmpltStoreRef = _tmplStoreDao.findByStoreTemplate(store.getId(), templateId);
                if (tmpltStoreRef != null) {
                    if (tmpltStoreRef.getDownloadState() == com.cloud.storage.VMTemplateStorageResourceAssoc.Status.DOWNLOADED) {
                        tmpltStore = (ImageStoreEntity)store;
                        if (tmpltStoreRef.getExtractUrl() != null) {
                            return tmpltStoreRef.getExtractUrl();
                        }
                        break;
                    }
                }
            }
        }

        if (tmpltStore == null) {
            throw new InvalidParameterValueException("The " + desc + " has not been downloaded ");
        }

        // Check if the url already exists
        if(tmpltStoreRef.getExtractUrl() != null){
            return tmpltStoreRef.getExtractUrl();
        }

        // Handle NFS to S3 object store migration case, we trigger template sync from NFS to S3 during extract template or copy template
        _tmpltSvr.syncTemplateToRegionStore(template, tmpltStore);

        TemplateInfo templateObject = _tmplFactory.getTemplate(templateId, tmpltStore);
        String extractUrl = tmpltStore.createEntityExtractUrl(templateObject.getInstallPath(), template.getFormat(), templateObject);
        tmpltStoreRef.setExtractUrl(extractUrl);
        tmpltStoreRef.setExtractUrlCreated(DateUtil.now());
        _tmplStoreDao.update(tmpltStoreRef.getId(), tmpltStoreRef);
        return extractUrl;
    }

    @Override
    public void prepareIsoForVmProfile(VirtualMachineProfile profile, DeployDestination dest) {
        UserVmVO vm = _userVmDao.findById(profile.getId());
        if (vm.getIsoId() != null) {
            Map<Volume, StoragePool> storageForDisks = dest.getStorageForDisks();
            Long poolId = null;
            TemplateInfo template;
            if (MapUtils.isNotEmpty(storageForDisks)) {
                for (StoragePool storagePool : storageForDisks.values()) {
                    if (poolId != null && storagePool.getId() != poolId) {
                        throw new CloudRuntimeException("Cannot determine where to download iso");
                    }
                    poolId = storagePool.getId();
                }
            }
            template = prepareIso(vm.getIsoId(), vm.getDataCenterId(), dest.getHost().getId(), poolId);

            if (template == null){
                logger.error("Failed to prepare ISO on secondary or cache storage");
                throw new CloudRuntimeException("Failed to prepare ISO on secondary or cache storage");
            }
            if (template.isBootable()) {
                profile.setBootLoaderType(BootloaderType.CD);
            }

            GuestOSVO guestOS = _guestOSDao.findById(template.getGuestOSId());
            String displayName = null;
            if (guestOS != null) {
                displayName = guestOS.getDisplayName();
            }

            TemplateObjectTO iso = (TemplateObjectTO)template.getTO();
            iso.setDirectDownload(template.isDirectDownload());
            iso.setGuestOsType(displayName);
            DiskTO disk = new DiskTO(iso, 3L, null, Volume.Type.ISO);
            profile.addDisk(disk);
        } else {
            TemplateObjectTO iso = new TemplateObjectTO();
            iso.setFormat(ImageFormat.ISO);
            DiskTO disk = new DiskTO(iso, 3L, null, Volume.Type.ISO);
            profile.addDisk(disk);
        }
    }

    private void prepareTemplateInOneStoragePool(final VMTemplateVO template, final StoragePoolVO pool) {
        logger.info("Schedule to preload template {} into primary storage {}", template, pool);
        if (pool.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
            List<StoragePoolVO> childDataStores = _poolDao.listChildStoragePoolsInDatastoreCluster(pool.getId());
            logger.debug("Schedule to preload template {} into child datastores of DataStore cluster: {}", template, pool);
            for (StoragePoolVO childDataStore :  childDataStores) {
                prepareTemplateInOneStoragePoolInternal(template, childDataStore);
            }
        } else {
            prepareTemplateInOneStoragePoolInternal(template, pool);
        }
    }

    private void prepareTemplateInOneStoragePoolInternal(final VMTemplateVO template, final StoragePoolVO pool) {
        _preloadExecutor.execute(new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                try {
                    reallyRun();
                } catch (Throwable e) {
                    logger.warn("Unexpected exception ", e);
                }
            }

            private void reallyRun() {
                logger.info("Start to preload template {} into primary storage {}", template, pool);
                StoragePool pol = (StoragePool)_dataStoreMgr.getPrimaryDataStore(pool.getId());
                prepareTemplateForCreate(template, pol);
                logger.info("End of preloading template {} into primary storage {}", template, pool);
            }
        });
    }

    public void prepareTemplateInAllStoragePools(final VMTemplateVO template, long zoneId) {
        List<StoragePoolVO> pools = _poolDao.listByStatus(StoragePoolStatus.Up);
        for (final StoragePoolVO pool : pools) {
            if (pool.getDataCenterId() == zoneId) {
                prepareTemplateInOneStoragePool(template, pool);
            } else {
                logger.info("Skip loading template {} into primary storage {} as pool " +
                        "zone {} is different from the requested zone {}", template::toString, pool::toString,
                        () -> _dcDao.findById(pool.getDataCenterId()), () -> _dcDao.findById(zoneId));
            }
        }
    }

    @Override
    @DB
    public VMTemplateStoragePoolVO prepareTemplateForCreate(VMTemplateVO templ, StoragePool pool) {
        VMTemplateVO template = _tmpltDao.findById(templ.getId(), true);

        long poolId = pool.getId();
        long templateId = template.getId();
        VMTemplateStoragePoolVO templateStoragePoolRef = null;
        TemplateDataStoreVO templateStoreRef = null;

        templateStoragePoolRef = _tmpltPoolDao.findByPoolTemplate(poolId, templateId, null);
        if (templateStoragePoolRef != null) {
            templateStoragePoolRef.setMarkedForGC(false);
            _tmpltPoolDao.update(templateStoragePoolRef.getId(), templateStoragePoolRef);

            if (templateStoragePoolRef.getDownloadState() == Status.DOWNLOADED) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Template {} has already been downloaded to pool {}", template, pool);
                }

                return templateStoragePoolRef;
            }
        }

        templateStoreRef = _tmplStoreDao.findByTemplateZoneDownloadStatus(templateId, pool.getDataCenterId(), VMTemplateStorageResourceAssoc.Status.DOWNLOADED);
        if (templateStoreRef == null) {
            logger.error("Unable to find a secondary storage host who has completely downloaded the template.");
            return null;
        }

        List<StoragePoolHostVO> vos = _poolHostDao.listByHostStatus(poolId, com.cloud.host.Status.Up);
        if (vos == null || vos.isEmpty()) {
            throw new CloudRuntimeException(String.format("Cannot download %s to pool %s since there is no host in the Up state connected to this pool", template, pool));
        }

        if (templateStoragePoolRef == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Downloading template {} to pool {}", template, pool);
            }
            DataStore srcSecStore = _dataStoreMgr.getDataStore(templateStoreRef.getDataStoreId(), DataStoreRole.Image);
            TemplateInfo srcTemplate = _tmplFactory.getTemplate(templateId, srcSecStore);

            AsyncCallFuture<TemplateApiResult> future = _tmpltSvr.prepareTemplateOnPrimary(srcTemplate, pool);
            try {
                TemplateApiResult result = future.get();
                if (result.isFailed()) {
                    logger.debug("prepare template failed:" + result.getResult());
                    return null;
                }

                return _tmpltPoolDao.findByPoolTemplate(poolId, templateId, null);
            } catch (Exception ex) {
                logger.debug("failed to copy template from image store {} to primary storage", srcSecStore);
            }
        }

        return null;
    }

    @Override
    public String getChecksum(DataStore store, String templatePath, String algorithm) {
        EndPoint ep = _epSelector.select(store);
        ComputeChecksumCommand cmd = new ComputeChecksumCommand(store.getTO(), templatePath, algorithm);
        Answer answer = null;
        if (ep == null) {
            String errMsg = "No remote endpoint to send command, check if host or ssvm is down?";
            logger.error(errMsg);
            answer = new Answer(cmd, false, errMsg);
        } else {
            answer = ep.sendMessage(cmd);
        }
        if (answer != null && answer.getResult()) {
            return answer.getDetails();
        }
        return null;
    }

    @Override
    @DB
    public boolean resetTemplateDownloadStateOnPool(long templateStoragePoolRefId) {
        // have to use the same lock that prepareTemplateForCreate use to
        // maintain state consistency
        VMTemplateStoragePoolVO templateStoragePoolRef = _tmpltPoolDao.acquireInLockTable(templateStoragePoolRefId, 1200);

        if (templateStoragePoolRef == null) {
            logger.warn("resetTemplateDownloadStateOnPool failed - unable to lock TemplateStorgePoolRef " + templateStoragePoolRefId);
            return false;
        }

        try {
            templateStoragePoolRef.setTemplateSize(0);
            templateStoragePoolRef.setDownloadState(VMTemplateStorageResourceAssoc.Status.NOT_DOWNLOADED);

            _tmpltPoolDao.update(templateStoragePoolRefId, templateStoragePoolRef);
        } finally {
            _tmpltPoolDao.releaseFromLockTable(templateStoragePoolRefId);
        }

        return true;
    }

    @Override
    @DB
    public boolean copy(long userId, VMTemplateVO template, DataStore srcSecStore, DataCenterVO dstZone) throws StorageUnavailableException, ResourceAllocationException {
        long tmpltId = template.getId();
        long dstZoneId = dstZone.getId();
        // find all eligible image stores for the destination zone
        List<DataStore> dstSecStores = _dataStoreMgr.getImageStoresByScopeExcludingReadOnly(new ZoneScope(dstZoneId));
        if (dstSecStores == null || dstSecStores.isEmpty()) {
            throw new StorageUnavailableException("Destination zone is not ready, no image store associated", DataCenter.class, dstZone.getId());
        }
        AccountVO account = _accountDao.findById(template.getAccountId());
        // find the size of the template to be copied
        TemplateDataStoreVO srcTmpltStore = _tmplStoreDao.findByStoreTemplate(srcSecStore.getId(), tmpltId);

        _resourceLimitMgr.checkResourceLimit(account, ResourceType.template);
        _resourceLimitMgr.checkResourceLimit(account, ResourceType.secondary_storage, new Long(srcTmpltStore.getSize()).longValue());

        // Event details
        String copyEventType;
        if (template.getFormat().equals(ImageFormat.ISO)) {
            copyEventType = EventTypes.EVENT_ISO_COPY;
        } else {
            copyEventType = EventTypes.EVENT_TEMPLATE_COPY;
        }

        TemplateInfo srcTemplate = _tmplFactory.getTemplate(template.getId(), srcSecStore);
        // Copy will just find one eligible image store for the destination zone
        // and copy template there, not propagate to all image stores
        // for that zone
        for (DataStore dstSecStore : dstSecStores) {
            TemplateDataStoreVO dstTmpltStore = _tmplStoreDao.findByStoreTemplate(dstSecStore.getId(), tmpltId);
            if (dstTmpltStore != null && dstTmpltStore.getDownloadState() == Status.DOWNLOADED) {
                return true; // already downloaded on this image store
            }
            if (dstTmpltStore != null && dstTmpltStore.getDownloadState() != Status.DOWNLOAD_IN_PROGRESS) {
                _tmplStoreDao.removeByTemplateStore(tmpltId, dstSecStore.getId());
            }

            AsyncCallFuture<TemplateApiResult> future = _tmpltSvr.copyTemplate(srcTemplate, dstSecStore);
            try {
                TemplateApiResult result = future.get();
                if (result.isFailed()) {
                    logger.debug("copy template failed for image store {}: {}", dstSecStore, result.getResult());
                    continue; // try next image store
                }

                _tmpltDao.addTemplateToZone(template, dstZoneId);

                if (account.getId() != Account.ACCOUNT_ID_SYSTEM) {
                    UsageEventUtils.publishUsageEvent(copyEventType, account.getId(), dstZoneId, tmpltId, null, null, null, srcTmpltStore.getPhysicalSize(),
                            srcTmpltStore.getSize(), template.getClass().getName(), template.getUuid());
                }

                // Copy every Datadisk template that belongs to the template to Destination zone
                List<VMTemplateVO> dataDiskTemplates = _tmpltDao.listByParentTemplatetId(template.getId());
                if (dataDiskTemplates != null && !dataDiskTemplates.isEmpty()) {
                    for (VMTemplateVO dataDiskTemplate : dataDiskTemplates) {
                        logger.debug("Copying {} for source template {}. Copy all Datadisk templates to destination datastore {}", dataDiskTemplates.size(), template, dstSecStore);
                        TemplateInfo srcDataDiskTemplate = _tmplFactory.getTemplate(dataDiskTemplate.getId(), srcSecStore);
                        AsyncCallFuture<TemplateApiResult> dataDiskCopyFuture = _tmpltSvr.copyTemplate(srcDataDiskTemplate, dstSecStore);
                        try {
                            TemplateApiResult dataDiskCopyResult = dataDiskCopyFuture.get();
                            if (dataDiskCopyResult.isFailed()) {
                                logger.error("Copy of datadisk template: {} to image store: {} failed with error: {} , will try copying the next one", srcDataDiskTemplate, dstSecStore, dataDiskCopyResult.getResult());
                                continue; // Continue to copy next Datadisk template
                            }
                            _tmpltDao.addTemplateToZone(dataDiskTemplate, dstZoneId);
                            _resourceLimitMgr.incrementResourceCount(dataDiskTemplate.getAccountId(), ResourceType.secondary_storage, dataDiskTemplate.getSize());
                        } catch (Exception ex) {
                            logger.error("Failed to copy datadisk template: {} to image store: {} , will try copying the next one", srcDataDiskTemplate, dstSecStore);
                        }
                    }
                }
            } catch (Exception ex) {
                logger.debug("failed to copy template to image store:{} ,will try next one", dstSecStore);
            }
        }
        return true;

    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TEMPLATE_COPY, eventDescription = "copying template", async = true)
    public VirtualMachineTemplate copyTemplate(CopyTemplateCmd cmd) throws StorageUnavailableException, ResourceAllocationException {
        Long templateId = cmd.getId();
        Long userId = CallContext.current().getCallingUserId();
        Long sourceZoneId = cmd.getSourceZoneId();
        List<Long> destZoneIds = cmd.getDestinationZoneIds();
        Account caller = CallContext.current().getCallingAccount();

        // Verify parameters
        VMTemplateVO template = _tmpltDao.findById(templateId);
        if (template == null || template.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find template with id");
        }

        // Verify template is not Datadisk template
        if (template.getTemplateType().equals(TemplateType.DATADISK)) {
            throw new InvalidParameterValueException(String.format("Template %s is of type Datadisk. Cannot copy Datadisk templates.", template));
        }

        if (sourceZoneId != null) {
            if (destZoneIds!= null && destZoneIds.contains(sourceZoneId)) {
                throw new InvalidParameterValueException("Please specify different source and destination zones.");
            }

            DataCenterVO sourceZone = _dcDao.findById(sourceZoneId);
            if (sourceZone == null) {
                throw new InvalidParameterValueException("Please specify a valid source zone.");
            }
        }

        Map<Long, DataCenterVO> dataCenterVOs = new HashMap();

        for (Long destZoneId: destZoneIds) {
            DataCenterVO dstZone = _dcDao.findById(destZoneId);
            if (dstZone == null) {
                throw new InvalidParameterValueException("Please specify a valid destination zone.");
            }
            dataCenterVOs.put(destZoneId, dstZone);
        }

        _accountMgr.checkAccess(caller, AccessType.OperateEntry, true, template);

        List<String> failedZones = new ArrayList<>();

        boolean success = false;
        if (template.getHypervisorType() == HypervisorType.BareMetal || template.getHypervisorType() == HypervisorType.External) {
            if (template.isCrossZones()) {
                logger.debug("Template {} is cross-zone, don't need to copy", template);
                return template;
            }
            for (Long destZoneId: destZoneIds) {
                if (!addTemplateToZone(template, destZoneId, sourceZoneId)) {
                    failedZones.add(dataCenterVOs.get(destZoneId).getName());
                }
            }
        } else {
            DataStore srcSecStore = null;
            if (sourceZoneId != null) {
                // template is on zone-wide secondary storage
                srcSecStore = getImageStore(sourceZoneId, templateId);
            } else {
                // template is on region store
                srcSecStore = getImageStore(templateId);
            }

            if (srcSecStore == null) {
                throw new InvalidParameterValueException(String.format("There is no template %s ready on image store.", template));
            }

            if (template.isCrossZones()) {
                // sync template from cache store to region store if it is not there, for cases where we are going to migrate existing NFS to S3.
                _tmpltSvr.syncTemplateToRegionStore(template, srcSecStore);
            }
            for (Long destZoneId : destZoneIds) {
                DataStore dstSecStore = getImageStore(destZoneId, templateId);
                if (dstSecStore != null) {
                    logger.debug("There is template {} in secondary storage {} in zone {} , don't need to copy", template, dstSecStore, dataCenterVOs.get(destZoneId));
                    continue;
                }
                if (!copy(userId, template, srcSecStore, dataCenterVOs.get(destZoneId))) {
                    failedZones.add(dataCenterVOs.get(destZoneId).getName());
                }
                else{
                    if (template.getSize() != null) {
                        // increase resource count
                        long accountId = template.getAccountId();
                        _resourceLimitMgr.incrementResourceCount(accountId, ResourceType.secondary_storage, template.getSize());
                    }
                }
            }
        }

        if ((destZoneIds != null) && (destZoneIds.size() > failedZones.size())){
            if (!failedZones.isEmpty()) {
                logger.debug("There were failures when copying template to zones: " +
                        StringUtils.listToCsvTags(failedZones));
            }
            return template;
        } else {
            throw new CloudRuntimeException("Failed to copy template");
        }
    }

    private boolean addTemplateToZone(VMTemplateVO template, long dstZoneId, long sourceZoneid) throws ResourceAllocationException{
        long tmpltId = template.getId();
        DataCenterVO dstZone = _dcDao.findById(dstZoneId);
        DataCenterVO sourceZone = _dcDao.findById(sourceZoneid);

        AccountVO account = _accountDao.findById(template.getAccountId());


        _resourceLimitMgr.checkResourceLimit(account, ResourceType.template);

        try {
            _tmpltDao.addTemplateToZone(template, dstZoneId);
            return true;
        } catch (Exception ex) {
            logger.debug("failed to copy template from Zone: {} to Zone: {}", sourceZone, dstZone);
        }
        return false;
    }

    @Override
    public boolean delete(long userId, long templateId, Long zoneId) {
        VMTemplateVO template = _tmpltDao.findById(templateId);
        if (template == null || template.getRemoved() != null) {
            throw new InvalidParameterValueException("Please specify a valid template.");
        }

        TemplateAdapter adapter = getAdapter(template.getHypervisorType());
        return adapter.delete(new TemplateProfile(userId, template, zoneId));
    }

    @Override
    public List<VMTemplateStoragePoolVO> getUnusedTemplatesInPool(StoragePoolVO pool) {
        List<VMTemplateStoragePoolVO> unusedTemplatesInPool = new ArrayList<VMTemplateStoragePoolVO>();
        List<VMTemplateStoragePoolVO> allTemplatesInPool = _tmpltPoolDao.listByPoolId(pool.getId());

        for (VMTemplateStoragePoolVO templatePoolVO : allTemplatesInPool) {
            VMTemplateVO template = _tmpltDao.findByIdIncludingRemoved(templatePoolVO.getTemplateId());

            // If this is a routing template, consider it in use
            if (template.getTemplateType() == TemplateType.SYSTEM) {
                continue;
            }

            // If the template is not yet downloaded to the pool, consider it in
            // use
            if (templatePoolVO.getDownloadState() != Status.DOWNLOADED) {
                continue;
            }

            if (template.getFormat() != ImageFormat.ISO && !_volumeDao.isAnyVolumeActivelyUsingTemplateOnPool(template.getId(), pool.getId())) {
                unusedTemplatesInPool.add(templatePoolVO);
            }
        }

        return unusedTemplatesInPool;
    }

    @Override
    @DB
    public void evictTemplateFromStoragePool(VMTemplateStoragePoolVO templatePoolVO) {
        // Need to hold the lock; otherwise, another thread may create a volume from the template at the same time.
        // Assumption here is that we will hold the same lock during create volume from template.
        VMTemplateStoragePoolVO templatePoolRef = _tmpltPoolDao.acquireInLockTable(templatePoolVO.getId());

        if (templatePoolRef == null) {
            logger.debug("Can't acquire the lock for template pool ref: {}", templatePoolVO);

            return;
        }

        PrimaryDataStore pool = (PrimaryDataStore)_dataStoreMgr.getPrimaryDataStore(templatePoolVO.getPoolId());
        TemplateInfo template = _tmplFactory.getTemplateOnPrimaryStorage(templatePoolRef.getTemplateId(), pool, templatePoolRef.getDeploymentOption());

        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Evicting " + templatePoolVO);
            }

            if (pool.isManaged()) {
                // For managed store, just delete the template volume.
                AsyncCallFuture<TemplateApiResult> future = _tmpltSvr.deleteTemplateOnPrimary(template, pool);
                TemplateApiResult result = future.get();

                if (result.isFailed()) {
                    logger.debug("Failed to delete template {} from storage pool {}", template, pool);
                } else {
                    // Remove the templatePoolVO.
                    if (_tmpltPoolDao.remove(templatePoolVO.getId())) {
                        logger.debug("Successfully evicted template {} from storage pool {}", template, pool);
                    }
                }
            } else {
                DestroyCommand cmd = new DestroyCommand(pool, templatePoolVO);
                Answer answer = _storageMgr.sendToPool(pool, cmd);

                if (answer != null && answer.getResult()) {
                    // Remove the templatePoolVO.
                    if (_tmpltPoolDao.remove(templatePoolVO.getId())) {
                        logger.debug("Successfully evicted template {} from storage pool {}", template, pool);
                    }
                } else {
                    logger.info("Will retry evict template {} from storage pool {}", template, pool);
                }
            }
        } catch (StorageUnavailableException | InterruptedException | ExecutionException e) {
            logger.info("Storage is unavailable currently. Will retry evicte template {} from storage pool {}", template, pool);
        } finally {
            _tmpltPoolDao.releaseFromLockTable(templatePoolRef.getId());
        }
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        _preloadExecutor = Executors.newFixedThreadPool(TemplatePreloaderPoolSize.value(), new NamedThreadFactory("Template-Preloader"));

        return true;
    }

    protected TemplateManagerImpl() {
    }

    @Override
    public boolean templateIsDeleteable(long templateId) {
        List<UserVmJoinVO> userVmUsingIso = _userVmJoinDao.listActiveByIsoId(templateId);
        // check if there is any Vm using this ISO. We only need to check the
        // case where templateId is an ISO since
        // VM can be launched from ISO in secondary storage, while template will
        // always be copied to
        // primary storage before deploying VM.
        if (!userVmUsingIso.isEmpty()) {
            logger.debug("ISO " + templateId + " is not deleteable because it is attached to " + userVmUsingIso.size() + " VMs");
            return false;
        }

        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ISO_DETACH, eventDescription = "detaching ISO", async = true)
    public boolean detachIso(long vmId, Long isoParamId, Boolean... extraParams) {
        Account caller = CallContext.current().getCallingAccount();
        Long userId = CallContext.current().getCallingUserId();

        boolean forced = extraParams != null && extraParams.length > 0 ? extraParams[0] : false;
        boolean isVirtualRouter = extraParams != null && extraParams.length > 1 ? extraParams[1] : false;

        // Verify input parameters
        VirtualMachine virtualMachine = !isVirtualRouter ? _userVmDao.findById(vmId) : _vmInstanceDao.findById(vmId);
        if (virtualMachine == null || (isVirtualRouter && virtualMachine.getType() != VirtualMachine.Type.DomainRouter)) {
            throw new InvalidParameterValueException("Please specify a valid VM.");
        }

        _accountMgr.checkAccess(caller, null, true, virtualMachine);

        Long isoId = !isVirtualRouter ? ((UserVm) virtualMachine).getIsoId() : isoParamId;
        if (isoId == null) {
            throw new InvalidParameterValueException("The specified VM has no ISO attached to it.");
        }
        CallContext.current().setEventDetails("Vm Id: " + virtualMachine.getUuid() + " ISO Id: " + isoId);

        State vmState = virtualMachine.getState();
        if (vmState != State.Running && vmState != State.Stopped) {
            throw new InvalidParameterValueException("Please specify a VM that is either Stopped or Running.");
        }

        boolean result = attachISOToVM(vmId, userId, isoId, false, forced, isVirtualRouter); // attach=false
        // => detach
        if (result) {
            return result;
        } else {
            throw new CloudRuntimeException("Failed to detach iso");
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ISO_ATTACH, eventDescription = "attaching ISO", async = true)
    public boolean attachIso(long isoId, long vmId, Boolean... extraParams) {
        Account caller = CallContext.current().getCallingAccount();
        Long userId = CallContext.current().getCallingUserId();

        boolean forced = extraParams != null && extraParams.length > 0 ? extraParams[0] : false;
        boolean isVirtualRouter = extraParams != null && extraParams.length > 1 ? extraParams[1] : false;

        // Verify input parameters
        VirtualMachine vm = _userVmDao.findById(vmId);
        if (vm == null) {
            if (isVirtualRouter) {
                vm = _vmInstanceDao.findById(vmId);
                if (vm == null) {
                    throw new InvalidParameterValueException("Unable to find a virtual machine with id " + vmId);
                } else if (vm.getType() != VirtualMachine.Type.DomainRouter) {
                    throw new InvalidParameterValueException("Unable to find a virtual router with id " + vmId);
                }
            } else {
                throw new InvalidParameterValueException("Unable to find a virtual machine with id " + vmId);
            }
        }
        if (vm instanceof UserVm && UserVmManager.SHAREDFSVM.equals(((UserVm) vm).getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }

        if (HypervisorType.External.equals(vm.getHypervisorType())) {
            throw new InvalidParameterValueException("Attach ISO operation is not allowed for External hypervisor type");
        }

        VMTemplateVO iso = _tmpltDao.findById(isoId);
        if (iso == null || iso.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find an ISO with id " + isoId);
        }

        if (!TemplateType.PERHOST.equals(iso.getTemplateType())) {
            VMTemplateZoneVO exists = _tmpltZoneDao.findByZoneTemplate(vm.getDataCenterId(), isoId);
            if (null == exists) {
                throw new InvalidParameterValueException("ISO is not available in the zone the VM is in.");
            }
        }

        // check permissions
        // check if caller has access to VM and ISO
        // and also check if the VM's owner has access to the ISO.

        _accountMgr.checkAccess(caller, null, false, iso, vm);

        Account vmOwner = _accountDao.findById(vm.getAccountId());
        _accountMgr.checkAccess(vmOwner, null, false, iso, vm);

        State vmState = vm.getState();
        if (vmState != State.Running && vmState != State.Stopped) {
            throw new InvalidParameterValueException("Please specify a VM that is either Stopped or Running.");
        }

        if (XS_TOOLS_ISO.equals(iso.getUniqueName()) && vm.getHypervisorType() != Hypervisor.HypervisorType.XenServer) {
            throw new InvalidParameterValueException("Cannot attach Xenserver PV drivers to incompatible hypervisor " + vm.getHypervisorType());
        }

        if (VMWARE_TOOLS_ISO.equals(iso.getUniqueName()) && vm.getHypervisorType() != Hypervisor.HypervisorType.VMware) {
            throw new InvalidParameterValueException("Cannot attach VMware tools drivers to incompatible hypervisor " + vm.getHypervisorType());
        }
        boolean result = attachISOToVM(vmId, userId, isoId, true, forced, isVirtualRouter);
        if (result) {
            return result;
        } else {
            throw new CloudRuntimeException("Failed to attach iso");
        }
    }

    // for ISO, we need to consider whether to copy to cache storage or not if it is not on NFS, since our hypervisor resource always assumes that they are in NFS
    @Override
    public TemplateInfo prepareIso(long isoId, long dcId, Long hostId, Long poolId) {
        TemplateInfo tmplt;
        boolean bypassed = false;
        if (_tmplFactory.isTemplateMarkedForDirectDownload(isoId)) {
            tmplt = _tmplFactory.getReadyBypassedTemplateOnPrimaryStore(isoId, poolId, hostId);
            bypassed = true;
        } else {
            tmplt = _tmplFactory.getReadyTemplateOnImageStore(isoId, dcId);
        }

        if (tmplt == null || tmplt.getFormat() != ImageFormat.ISO) {
            logger.warn("ISO: " + isoId + " does not exist in vm_template table");
            return null;
        }

        if (!bypassed && tmplt.getDataStore() != null && !(tmplt.getDataStore().getTO() instanceof NfsTO)) {
            // if it is s3, need to download into cache storage first
            Scope destScope = new ZoneScope(dcId);
            TemplateInfo cacheData = (TemplateInfo)cacheMgr.createCacheObject(tmplt, destScope);
            if (cacheData == null) {
                logger.error("Failed in copy iso from S3 to cache storage");
                return null;
            }
            return cacheData;
        } else {
            return tmplt;
        }
    }

    private boolean attachISOToVM(long vmId, long isoId, boolean attach, boolean forced, boolean isVirtualRouter) {
        VirtualMachine vm = !isVirtualRouter ? _userVmDao.findById(vmId) : _vmInstanceDao.findById(vmId);

        if (vm == null || (isVirtualRouter && vm.getType() != VirtualMachine.Type.DomainRouter)) {
            return false;
        } else if (vm.getState() != State.Running) {
            return true;
        }

        // prepare ISO ready to mount on hypervisor resource level
        TemplateInfo tmplt = prepareIso(isoId, vm.getDataCenterId(), vm.getHostId(), null);
        if (tmplt == null) {
            logger.error("Failed to prepare ISO ready to mount on hypervisor resource level");
            throw new CloudRuntimeException("Failed to prepare ISO ready to mount on hypervisor resource level");
        }
        String vmName = vm.getInstanceName();

        HostVO host = _hostDao.findById(vm.getHostId());
        if (host == null) {
            logger.warn("Host: " + vm.getHostId() + " does not exist");
            return false;
        }

        DataTO isoTO = tmplt.getTO();
        DiskTO disk = new DiskTO(isoTO, null, null, Volume.Type.ISO);

        HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vm.getHypervisorType());
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        VirtualMachineTO vmTO = hvGuru.implement(profile);

        Command cmd = null;
        if (attach) {
            cmd = new AttachCommand(disk, vmName, vmTO.getDetails());
            ((AttachCommand)cmd).setForced(forced);
        } else {
            cmd = new DettachCommand(disk, vmName, vmTO.getDetails());
            ((DettachCommand)cmd).setForced(forced);
        }
        Answer a = _agentMgr.easySend(vm.getHostId(), cmd);
        return (a != null && a.getResult());
    }

    private boolean attachISOToVM(long vmId, long userId, long isoId, boolean attach, boolean forced, boolean isVirtualRouter) {
        UserVmVO vm = _userVmDao.findById(vmId);
        VMTemplateVO iso = _tmpltDao.findById(isoId);

        boolean success = attachISOToVM(vmId, isoId, attach, forced, isVirtualRouter);
        if (success && attach && !isVirtualRouter) {
            vm.setIsoId(iso.getId());
            _userVmDao.update(vmId, vm);
        }
        if (success && !attach && !isVirtualRouter) {
            vm.setIsoId(null);
            _userVmDao.update(vmId, vm);
        }
        return success;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TEMPLATE_DELETE, eventDescription = "deleting template", async = true)
    public boolean deleteTemplate(DeleteTemplateCmd cmd) {
        Long templateId = cmd.getId();
        Account caller = CallContext.current().getCallingAccount();

        VMTemplateVO template = _tmpltDao.findById(templateId);
        if (template == null) {
            throw new InvalidParameterValueException("unable to find template with id " + templateId);
        }

        List<VMInstanceVO> vmInstanceVOList;
        if(cmd.getZoneId() != null) {
            vmInstanceVOList = _vmInstanceDao.listNonExpungedByZoneAndTemplate(cmd.getZoneId(), templateId);
        }
        else {
            vmInstanceVOList = _vmInstanceDao.listNonExpungedByTemplate(templateId);
        }
        if(!cmd.isForced() && CollectionUtils.isNotEmpty(vmInstanceVOList)) {
            final String message = String.format("Unable to delete template: %s because VM instances: [%s] are using it.",  template, Joiner.on(",").join(vmInstanceVOList));
            logger.warn(message);
            throw new InvalidParameterValueException(message);
        }

        _accountMgr.checkAccess(caller, AccessType.OperateEntry, true, template);

        if (template.getFormat() == ImageFormat.ISO) {
            throw new InvalidParameterValueException("Please specify a valid template.");
        }

        VnfTemplateUtils.validateApiCommandParams(cmd, template);

        TemplateAdapter adapter = getAdapter(template.getHypervisorType());
        TemplateProfile profile = adapter.prepareDelete(cmd);
        return adapter.delete(profile);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ISO_DELETE, eventDescription = "deleting iso", async = true)
    public boolean deleteIso(DeleteIsoCmd cmd) {
        Long templateId = cmd.getId();
        Account caller = CallContext.current().getCallingAccount();
        Long zoneId = cmd.getZoneId();

        VMTemplateVO template = _tmpltDao.findById(templateId);
        if (template == null) {
            throw new InvalidParameterValueException("unable to find iso with id " + templateId);
        }

        _accountMgr.checkAccess(caller, AccessType.OperateEntry, true, template);

        if (template.getFormat() != ImageFormat.ISO) {
            throw new InvalidParameterValueException("Please specify a valid iso.");
        }

        // check if there is any VM using this ISO.
        if (!templateIsDeleteable(templateId)) {
            throw new InvalidParameterValueException("Unable to delete iso, as it's used by other vms");
        }

        if (zoneId != null && (_dataStoreMgr.getImageStoreWithFreeCapacity(zoneId) == null)) {
            throw new InvalidParameterValueException("Failed to find a secondary storage store in the specified zone.");
        }

        TemplateAdapter adapter = getAdapter(template.getHypervisorType());
        TemplateProfile profile = adapter.prepareDelete(cmd);
        boolean result = adapter.delete(profile);
        if (result) {
            return true;
        } else {
            throw new CloudRuntimeException("Failed to delete ISO");
        }
    }

    @Override
    public List<String> listTemplatePermissions(BaseListTemplateOrIsoPermissionsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long id = cmd.getId();

        if (id.equals(Long.valueOf(1))) {
            throw new PermissionDeniedException("unable to list permissions for " + cmd.getMediaType() + " with id " + id);
        }

        VirtualMachineTemplate template = _tmpltDao.findById(id);
        if (template == null) {
            throw new InvalidParameterValueException("unable to find " + cmd.getMediaType() + " with id " + id);
        }

        if (cmd instanceof ListTemplatePermissionsCmd) {
            if (template.getFormat().equals(ImageFormat.ISO)) {
                throw new InvalidParameterValueException("Please provide a valid template");
            }
        } else if (cmd instanceof ListIsoPermissionsCmd) {
            if (!template.getFormat().equals(ImageFormat.ISO)) {
                throw new InvalidParameterValueException("Please provide a valid iso");
            }
        }

        if (!template.isPublicTemplate()) {
            _accountMgr.checkAccess(caller, null, true, template);
        }

        List<String> accountNames = new ArrayList<String>();
        List<LaunchPermissionVO> permissions = _launchPermissionDao.findByTemplate(id);
        if ((permissions != null) && !permissions.isEmpty()) {
            for (LaunchPermissionVO permission : permissions) {
                Account acct = _accountDao.findById(permission.getAccountId());
                accountNames.add(acct.getAccountName());
            }
        }

        // also add the owner if not public
        if (!template.isPublicTemplate()) {
            Account templateOwner = _accountDao.findById(template.getAccountId());
            accountNames.add(templateOwner.getAccountName());
        }

        return accountNames;
    }

    @DB
    @Override
    public boolean updateTemplateOrIsoPermissions(BaseUpdateTemplateOrIsoPermissionsCmd cmd) {
        // Input validation
        final Long id = cmd.getId();
        final Account caller = CallContext.current().getCallingAccount();
        final User user = CallContext.current().getCallingUser();
        List<String> accountNames = cmd.getAccountNames();
        List<Long> projectIds = cmd.getProjectIds();
        Boolean isFeatured = cmd.isFeatured();
        Boolean isPublic = cmd.isPublic();
        Boolean isExtractable = cmd.isExtractable();
        String operation = cmd.getOperation();
        String mediaType = "";

        VMTemplateVO template = _tmpltDao.findById(id);

        if (template == null) {
            throw new InvalidParameterValueException("unable to find " + mediaType + " with id " + id);
        }

        if (cmd instanceof UpdateTemplatePermissionsCmd) {
            mediaType = "template";
            if (template.getFormat().equals(ImageFormat.ISO)) {
                throw new InvalidParameterValueException("Please provide a valid template");
            }
        }
        if (cmd instanceof UpdateIsoPermissionsCmd) {
            mediaType = "iso";
            if (!template.getFormat().equals(ImageFormat.ISO)) {
                throw new InvalidParameterValueException("Please provide a valid iso");
            }
        }

        // convert projectIds to accountNames
        if (projectIds != null) {
            // CS-17842, initialize accountNames list
            if (accountNames == null) {
                accountNames = new ArrayList<String>();
            }
            for (Long projectId : projectIds) {
                Project project = _projectMgr.getProject(projectId);
                if (project == null) {
                    throw new InvalidParameterValueException("Unable to find project by id " + projectId);
                }

                if (!_projectMgr.canAccessProjectAccount(caller, project.getProjectAccountId())) {
                    throw new InvalidParameterValueException("Account " + caller + " can't access project id=" + project.getUuid());
                }
                accountNames.add(_accountMgr.getAccount(project.getProjectAccountId()).getAccountName());
            }
        }

        //_accountMgr.checkAccess(caller, AccessType.ModifyEntry, true, template);
        _accountMgr.checkAccess(caller, AccessType.OperateEntry, true, template); //TODO: should we replace all ModifyEntry as OperateEntry?

        // If the template is removed throw an error.
        if (template.getRemoved() != null) {
            logger.error("unable to update permissions for " + mediaType + " with id " + id + " as it is removed  ");
            throw new InvalidParameterValueException("unable to update permissions for " + mediaType + " with id " + id + " as it is removed ");
        }

        if (id.equals(Long.valueOf(1))) {
            throw new InvalidParameterValueException("unable to update permissions for " + mediaType + " with id " + id);
        }

        Long ownerId = template.getAccountId();
        Account owner = _accountMgr.getAccount(ownerId);
        if (ownerId == null) {
            // if there is no owner of the template then it's probably already a
            // public template (or domain private template) so
            // publishing to individual users is irrelevant
            throw new InvalidParameterValueException("Update template permissions is an invalid operation on template " + template.getName());
        }

        if (owner.getType() == Account.Type.PROJECT) {
            // if it is a project owned template/iso, the user must at least have access to be allowed to share it.
            _accountMgr.checkAccess(user, template);
        }

        // check configuration parameter(allow.public.user.templates) value for
        // the template owner
        boolean isAdmin = _accountMgr.isAdmin(caller.getId());
        boolean allowPublicUserTemplates = AllowPublicUserTemplates.valueIn(template.getAccountId());
        if (!isAdmin && !allowPublicUserTemplates && isPublic != null && isPublic) {
            throw new InvalidParameterValueException("Only private " + mediaType + "s can be created.");
        }

        if (accountNames != null) {
            if ((operation == null) || (!operation.equalsIgnoreCase("add") && !operation.equalsIgnoreCase("remove") && !operation.equalsIgnoreCase("reset"))) {
                throw new InvalidParameterValueException(
                    "Invalid operation on accounts, the operation must be either 'add' or 'remove' in order to modify launch permissions." + "  Given operation is: '" +
                        operation + "'");
            }
        }

        //Only admin or owner of the template should be able to change its permissions
        if (caller.getId() != ownerId && !isAdmin) {
            throw new InvalidParameterValueException("Unable to grant permission to account " + caller.getAccountName() + " as it is neither admin nor owner or the template");
        }

        VMTemplateVO updatedTemplate = _tmpltDao.createForUpdate();

        if (isPublic != null) {
            updatedTemplate.setPublicTemplate(isPublic.booleanValue());
        }

        if (isFeatured != null) {
            updatedTemplate.setFeatured(isFeatured.booleanValue());
        }

        if (isExtractable != null) {
            // Only Root admins and owners are allowed to change it for templates
            if (!template.getFormat().equals(ImageFormat.ISO) && caller.getId() != ownerId && !isAdmin) {
                throw new InvalidParameterValueException("Only ROOT admins and template owners are allowed to modify isExtractable attribute.");
            } else {
                // For Isos normal user can change it, as their are no derivatives.
                updatedTemplate.setExtractable(isExtractable.booleanValue());
            }
        }

        _tmpltDao.update(template.getId(), updatedTemplate);

        //when operation is add/remove, accountNames can not be null
        if (("add".equalsIgnoreCase(operation) || "remove".equalsIgnoreCase(operation)) && accountNames == null) {
            throw new InvalidParameterValueException("Operation " + operation + " requires accounts or projectIds to be passed in");
        }

        //Derive the domain id from the template owner as updateTemplatePermissions is not cross domain operation
        final Domain domain = _domainDao.findById(owner.getDomainId());
        if ("add".equalsIgnoreCase(operation)) {
            final List<String> accountNamesFinal = accountNames;
            final List<Long> accountIds = new ArrayList<Long>();
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    for (String accountName : accountNamesFinal) {
                        Account permittedAccount = _accountDao.findActiveAccount(accountName, domain.getId());
                        if (permittedAccount != null) {
                            if (permittedAccount.getId() == caller.getId()) {
                                continue; // don't grant permission to the template
                                // owner, they implicitly have permission
                            }
                            accountIds.add(permittedAccount.getId());
                            LaunchPermissionVO existingPermission = _launchPermissionDao.findByTemplateAndAccount(id, permittedAccount.getId());
                            if (existingPermission == null) {
                                LaunchPermissionVO launchPermission = new LaunchPermissionVO(id, permittedAccount.getId());
                                _launchPermissionDao.persist(launchPermission);
                            }
                        } else {
                            throw new InvalidParameterValueException("Unable to grant a launch permission to account " + accountName + " in domain id=" +
                                domain.getUuid() + ", account not found.  " + "No permissions updated, please verify the account names and retry.");
                }
            }
                }
            });

            // add ACL permission in IAM
            Map<String, Object> permit = new HashMap<String, Object>();
            permit.put(ApiConstants.ENTITY_TYPE, VirtualMachineTemplate.class);
            permit.put(ApiConstants.ENTITY_ID, id);
            permit.put(ApiConstants.ACCESS_TYPE, AccessType.UseEntry);
            permit.put(ApiConstants.ACCOUNTS, accountIds);
            _messageBus.publish(_name, EntityManager.MESSAGE_GRANT_ENTITY_EVENT, PublishScope.LOCAL, permit);
        } else if ("remove".equalsIgnoreCase(operation)) {
            List<Long> accountIds = new ArrayList<Long>();
            for (String accountName : accountNames) {
                Account permittedAccount = _accountDao.findActiveAccount(accountName, domain.getId());
                if (permittedAccount != null) {
                    accountIds.add(permittedAccount.getId());
                }
            }
            _launchPermissionDao.removePermissions(id, accountIds);
            // remove ACL permission in IAM
            Map<String, Object> permit = new HashMap<String, Object>();
            permit.put(ApiConstants.ENTITY_TYPE, VirtualMachineTemplate.class);
            permit.put(ApiConstants.ENTITY_ID, id);
            permit.put(ApiConstants.ACCESS_TYPE, AccessType.UseEntry);
            permit.put(ApiConstants.ACCOUNTS, accountIds);
            _messageBus.publish(_name, EntityManager.MESSAGE_REVOKE_ENTITY_EVENT, PublishScope.LOCAL, permit);
        } else if ("reset".equalsIgnoreCase(operation)) {
            // do we care whether the owning account is an admin? if the
            // owner is an admin, will we still set public to false?
            updatedTemplate = _tmpltDao.createForUpdate();
            updatedTemplate.setPublicTemplate(false);
            updatedTemplate.setFeatured(false);
            _tmpltDao.update(template.getId(), updatedTemplate);
            _launchPermissionDao.removeAllPermissions(id);
            _messageBus.publish(_name, TemplateManager.MESSAGE_RESET_TEMPLATE_PERMISSION_EVENT, PublishScope.LOCAL, template.getId());
        }
        return true;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_TEMPLATE_CREATE, eventDescription = "creating template", async = true)
    public VirtualMachineTemplate createPrivateTemplate(CreateTemplateCmd command) throws CloudRuntimeException {
        final long templateId = command.getEntityId();
        Long volumeId = command.getVolumeId();
        Long snapshotId = command.getSnapshotId();
        VMTemplateVO privateTemplate = null;
        final Long accountId = CallContext.current().getCallingAccountId();
        SnapshotVO snapshot = null;
        VolumeVO volume = null;
        Account caller = CallContext.current().getCallingAccount();
        boolean kvmSnapshotOnlyInPrimaryStorage = false;
        SnapshotInfo snapInfo = null;

        try {
            TemplateInfo tmplInfo = _tmplFactory.getTemplate(templateId, DataStoreRole.Image);
            long zoneId = 0;
            if (snapshotId != null) {
                snapshot = _snapshotDao.findById(snapshotId);
                if (command.getZoneId() == null) {
                    VolumeVO snapshotVolume = _volumeDao.findByIdIncludingRemoved(snapshot.getVolumeId());
                    zoneId = snapshotVolume.getDataCenterId();
                } else {
                    zoneId = command.getZoneId();
                }
            } else if (volumeId != null) {
                volume = _volumeDao.findById(volumeId);
                zoneId = volume.getDataCenterId();
            }
            DataStore store = _dataStoreMgr.getImageStoreWithFreeCapacity(zoneId);
            if (store == null) {
                throw new CloudRuntimeException("cannot find an image store for zone " + zoneId);
            }
            AsyncCallFuture<TemplateApiResult> future = null;

            if (snapshotId != null) {
                DataStoreRole dataStoreRole = snapshotHelper.getDataStoreRole(snapshot);
                kvmSnapshotOnlyInPrimaryStorage = snapshotHelper.isKvmSnapshotOnlyInPrimaryStorage(snapshot, dataStoreRole);
                snapInfo = _snapshotFactory.getSnapshotWithRoleAndZone(snapshotId, dataStoreRole, zoneId);

                boolean kvmIncrementalSnapshot = SnapshotManager.kvmIncrementalSnapshot.valueIn(_hostDao.findClusterIdByVolumeInfo(snapInfo.getBaseVolume()));

                if (dataStoreRole == DataStoreRole.Image || kvmSnapshotOnlyInPrimaryStorage) {
                    snapInfo = snapshotHelper.backupSnapshotToSecondaryStorageIfNotExists(snapInfo, dataStoreRole, snapshot, kvmSnapshotOnlyInPrimaryStorage);
                    _accountMgr.checkAccess(caller, null, true, snapInfo);
                    DataStore snapStore = snapInfo.getDataStore();

                    if (snapStore != null) {
                        store = snapStore; // pick snapshot image store to create template
                    }
                }
                if (kvmIncrementalSnapshot && DataStoreRole.Image.equals(dataStoreRole)) {
                    snapInfo = snapshotHelper.convertSnapshotIfNeeded(snapInfo);
                }

                future = _tmpltSvr.createTemplateFromSnapshotAsync(snapInfo, tmplInfo, store);
            } else if (volumeId != null) {
                VolumeInfo volInfo = _volFactory.getVolume(volumeId);
                if (volInfo == null) {
                    throw new InvalidParameterValueException("No such volume exist");
                }

                _accountMgr.checkAccess(caller, null, true, volInfo);
                future = _tmpltSvr.createTemplateFromVolumeAsync(volInfo, tmplInfo, store);
            } else {
                throw new CloudRuntimeException("Creating private Template need to specify snapshotId or volumeId");
            }

            CommandResult result = null;

            try {
                result = future.get();

                if (result.isFailed()) {
                    privateTemplate = null;
                    logger.debug("Failed to create template" + result.getResult());
                    throw new CloudRuntimeException("Failed to create template" + result.getResult());
                }

                // create entries in template_zone_ref table
                if (_dataStoreMgr.isRegionStore(store)) {
                    // template created on region store
                    _tmpltSvr.associateTemplateToZone(templateId, null);
                } else {
                    VMTemplateZoneVO templateZone = new VMTemplateZoneVO(zoneId, templateId, new Date());
                    _tmpltZoneDao.persist(templateZone);
                }

                privateTemplate = _tmpltDao.findById(templateId);
                TemplateDataStoreVO srcTmpltStore = _tmplStoreDao.findByStoreTemplate(store.getId(), templateId);
                UsageEventVO usageEvent =
                        new UsageEventVO(EventTypes.EVENT_TEMPLATE_CREATE, privateTemplate.getAccountId(), zoneId, privateTemplate.getId(), privateTemplate.getName(), null,
                                privateTemplate.getSourceTemplateId(), srcTmpltStore.getPhysicalSize(), privateTemplate.getSize());
                _usageEventDao.persist(usageEvent);
            } catch (InterruptedException e) {
                logger.debug("Failed to create template", e);
                throw new CloudRuntimeException("Failed to create template", e);
            } catch (ExecutionException e) {
                logger.debug("Failed to create template", e);
                throw new CloudRuntimeException("Failed to create template", e);
            }

        } finally {
            if (privateTemplate == null) {
                final VolumeVO volumeFinal = volume;
                final SnapshotVO snapshotFinal = snapshot;
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) {
                        // template_store_ref entries should have been removed using our
                        // DataObject.processEvent command in case of failure, but clean
                        // it up here to avoid
                        // some leftovers which will cause removing template from
                        // vm_template table fail.
                        _tmplStoreDao.deletePrimaryRecordsForTemplate(templateId);
                        // Remove the template_zone_ref record
                        _tmpltZoneDao.deletePrimaryRecordsForTemplate(templateId);
                        // Remove the template record
                        _tmpltDao.expunge(templateId);

                        // decrement resource count
                        if (accountId != null) {
                            _resourceLimitMgr.decrementResourceCount(accountId, ResourceType.template);
                            _resourceLimitMgr.decrementResourceCount(accountId, ResourceType.secondary_storage, new Long(volumeFinal != null ? volumeFinal.getSize()
                                    : snapshotFinal.getSize()));
                        }
                    }
                });

            }

            if (snapshotId != null) {
                snapshotHelper.expungeTemporarySnapshot(kvmSnapshotOnlyInPrimaryStorage, snapInfo);
            }
        }

        if (privateTemplate != null) {
            return privateTemplate;
        } else {
            throw new CloudRuntimeException("Failed to create a template");
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TEMPLATE_CREATE, eventDescription = "creating template", create = true)
    public VMTemplateVO createPrivateTemplateRecord(CreateTemplateCmd cmd, Account templateOwner) throws ResourceAllocationException {
        Account caller = CallContext.current().getCallingAccount();
        boolean isAdmin = (_accountMgr.isAdmin(caller.getId()));

        _accountMgr.checkAccess(caller, null, true, templateOwner);

        String name = cmd.getTemplateName();
        if ((org.apache.commons.lang3.StringUtils.isBlank(name)
                || (name.length() > VirtualMachineTemplate.MAXIMUM_TEMPLATE_NAME_LENGTH))) {
            throw new InvalidParameterValueException(String.format("Template name cannot be null and cannot be more %s characters", VirtualMachineTemplate.MAXIMUM_TEMPLATE_NAME_LENGTH));
        }

        if (cmd.getTemplateTag() != null) {
            if (!_accountService.isRootAdmin(caller.getId())) {
                throw new PermissionDeniedException("Parameter templatetag can only be specified by a Root Admin, permission denied");
            }
        }

        // do some parameter defaulting
        Integer bits = cmd.getBits();
        Boolean requiresHvm = cmd.getRequiresHvm();
        Boolean passwordEnabled = cmd.isPasswordEnabled();
        Boolean sshKeyEnabled = cmd.isSshKeyEnabled();
        Boolean isPublic = cmd.isPublic();
        Boolean featured = cmd.isFeatured();
        int bitsValue = ((bits == null) ? 64 : bits.intValue());
        boolean requiresHvmValue = ((requiresHvm == null) ? true : requiresHvm.booleanValue());
        boolean passwordEnabledValue = ((passwordEnabled == null) ? false : passwordEnabled.booleanValue());
        boolean sshKeyEnabledValue = ((sshKeyEnabled == null) ? false : sshKeyEnabled.booleanValue());
        if (isPublic == null) {
            isPublic = Boolean.FALSE;
        }
        boolean isDynamicScalingEnabled = cmd.isDynamicallyScalable();
        // check whether template owner can create public templates
        boolean allowPublicUserTemplates = AllowPublicUserTemplates.valueIn(templateOwner.getId());
        final Long zoneId = cmd.getZoneId();
        if (!isAdmin && !allowPublicUserTemplates && isPublic) {
            throw new PermissionDeniedException("Failed to create template " + name + ", only private templates can be created.");
        }

        Long volumeId = cmd.getVolumeId();
        Long snapshotId = cmd.getSnapshotId();
        if (zoneId != null && snapshotId == null) {
            throw new InvalidParameterValueException("Failed to create private template record, zone ID can only be specified together with snapshot ID.");
        }
        if ((volumeId == null) && (snapshotId == null)) {
            throw new InvalidParameterValueException("Failed to create private template record, neither volume ID nor snapshot ID were specified.");
        }
        if ((volumeId != null) && (snapshotId != null)) {
            throw new InvalidParameterValueException("Failed to create private template record, please specify only one of volume ID (" + volumeId +
                    ") and snapshot ID (" + snapshotId + ")");
        }

        HypervisorType hyperType;
        VolumeVO volume = null;
        SnapshotVO snapshot = null;
        VMTemplateVO privateTemplate = null;
        if (volumeId != null) { // create template from volume
            volume = _volumeDao.findById(volumeId);
            if (volume == null) {
                throw new InvalidParameterValueException("Failed to create private template record, unable to find volume " + volumeId);
            }
            // check permissions
            _accountMgr.checkAccess(caller, null, true, volume);

            // Don't support creating templates from encrypted volumes (yet)
            if (volume.getPassphraseId() != null) {
                throw new UnsupportedOperationException("Cannot create templates from encrypted volumes");
            }

            // If private template is created from Volume, check that the volume
            // will not be active when the private template is
            // created
            if (!_volumeMgr.volumeInactive(volume)) {
                String msg = String.format("Unable to create private template for volume: %s; volume is attached to a non-stopped VM, please stop the VM first", volume);
                if (logger.isInfoEnabled()) {
                    logger.info(msg);
                }
                throw new CloudRuntimeException(msg);
            }

            hyperType = _volumeDao.getHypervisorType(volumeId);
            if (HypervisorType.LXC.equals(hyperType)) {
                throw new InvalidParameterValueException(String.format("Template creation is not supported for LXC volume: %s", volume));
            }
        } else { // create template from snapshot
            snapshot = _snapshotDao.findById(snapshotId);
            if (snapshot == null) {
                throw new InvalidParameterValueException("Failed to create private template record, unable to find snapshot " + snapshotId);
            }
            // Volume could be removed so find including removed to record source template id.
            volume = _volumeDao.findByIdIncludingRemoved(snapshot.getVolumeId());

            // Don't support creating templates from encrypted volumes (yet)
            if (volume != null && volume.getPassphraseId() != null) {
                throw new UnsupportedOperationException("Cannot create templates from snapshots of encrypted volumes");
            }

            // check permissions
            _accountMgr.checkAccess(caller, null, true, snapshot);

            if (snapshot.getState() != Snapshot.State.BackedUp) {
                throw new InvalidParameterValueException(String.format("Snapshot %s is not in %s state yet and can't be used for template creation",
                        snapshot, Snapshot.State.BackedUp));
            }

            /*
             * // bug #11428. Operation not supported if vmware and snapshots
             * parent volume = ROOT if(snapshot.getHypervisorType() ==
             * HypervisorType.VMware && snapshotVolume.getVolumeType() ==
             * Type.DATADISK){ throw new UnsupportedServiceException(
             * "operation not supported, snapshot with id " + snapshotId +
             * " is created from Data Disk"); }
             */

            hyperType = snapshot.getHypervisorType();
        }

        if (zoneId != null) {
            DataCenterVO zone = _dcDao.findById(zoneId);
            if (zone == null) {
                throw new InvalidParameterValueException("Failed to create private template record, invalid zone specified");
            }
            if (DataCenter.Type.Edge.equals(zone.getType())) {
                throw new InvalidParameterValueException("Failed to create private template record, Edge zones do not support template creation from snapshots");
            }
        }

        _resourceLimitMgr.checkResourceLimit(templateOwner, ResourceType.template);
        _resourceLimitMgr.checkResourceLimit(templateOwner, ResourceType.secondary_storage, new Long(volume != null ? volume.getSize() : snapshot.getSize()).longValue());

        if (!isAdmin || featured == null) {
            featured = Boolean.FALSE;
        }
        Long guestOSId = cmd.getOsTypeId();
        GuestOSVO guestOS = _guestOSDao.findById(guestOSId);
        if (guestOS == null) {
            throw new InvalidParameterValueException("GuestOS with ID: " + guestOSId + " does not exist.");
        }

        Long nextTemplateId = _tmpltDao.getNextInSequence(Long.class, "id");
        String description = cmd.getDisplayText();
        boolean isExtractable = false;
        Long sourceTemplateId = null;
        CPU.CPUArch arch = CPU.CPUArch.amd64;
        if (volume != null) {
            VMTemplateVO template = ApiDBUtils.findTemplateById(volume.getTemplateId());
            isExtractable = template != null && template.isExtractable() && template.getTemplateType() != Storage.TemplateType.SYSTEM;
            if (template != null) {
                arch = template.getArch();
            }
            if (volume.getIsoId() != null && volume.getIsoId() != 0) {
                sourceTemplateId = volume.getIsoId();
            } else if (volume.getTemplateId() != null) {
                sourceTemplateId = volume.getTemplateId();
            }
        }
        String templateTag = cmd.getTemplateTag();
        if (templateTag != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Adding template tag: " + templateTag);
            }
        }
        privateTemplate = new VMTemplateVO(nextTemplateId, name, ImageFormat.RAW, isPublic, featured, isExtractable,
                TemplateType.USER, null, requiresHvmValue, bitsValue, templateOwner.getId(), null, description,
                passwordEnabledValue, guestOS.getId(), true, hyperType, templateTag, cmd.getDetails(), sshKeyEnabledValue, isDynamicScalingEnabled, false, false, arch, null);

        if (sourceTemplateId != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("This template is getting created from other template, setting source template Id to: " + sourceTemplateId);
            }
        }


        // for region wide storage, set cross zones flag
        List<ImageStoreVO> stores = _imgStoreDao.findRegionImageStores();
        if (!CollectionUtils.isEmpty(stores)) {
            privateTemplate.setCrossZones(true);
        }

        privateTemplate.setSourceTemplateId(sourceTemplateId);

        VMTemplateVO template = _tmpltDao.persist(privateTemplate);
        // Increment the number of templates
        if (template != null) {
            Map<String, String> details = new HashMap<String, String>();

            if (sourceTemplateId != null) {
                VMTemplateVO sourceTemplate = _tmpltDao.findById(sourceTemplateId);
                if (sourceTemplate != null && sourceTemplate.getDetails() != null) {
                    details.putAll(sourceTemplate.getDetails());
                }
            }

            if (volume != null) {
                Long vmId = volume.getInstanceId();
                if (vmId != null) {
                    UserVmVO userVm = _userVmDao.findById(vmId);
                    if (userVm != null) {
                        _userVmDao.loadDetails(userVm);
                        Map<String, String> vmDetails = userVm.getDetails();
                        vmDetails = vmDetails.entrySet()
                                .stream()
                                .filter(map -> map.getValue() != null)
                                .collect(Collectors.toMap(map -> map.getKey(), map -> map.getValue()));
                        details.putAll(vmDetails);
                    }
                }
            }
            if (cmd.getDetails() != null) {
                details.remove(VmDetailConstants.ENCRYPTED_PASSWORD); // new password will be generated during vm deployment from password enabled template
                details.putAll(cmd.getDetails());
            }
            if (!details.isEmpty()) {
                privateTemplate.setDetails(details);
                _tmpltDao.saveDetails(privateTemplate);
            }

            _resourceLimitMgr.incrementResourceCount(templateOwner.getId(), ResourceType.template);
            _resourceLimitMgr.incrementResourceCount(templateOwner.getId(), ResourceType.secondary_storage,
                    new Long(volume != null ? volume.getSize() : snapshot.getSize()));
        }

        if (template != null) {
            CallContext.current().putContextParameter(VirtualMachineTemplate.class, template.getUuid());
            return template;
        } else {
            throw new CloudRuntimeException("Failed to create a template");
        }

    }

    @Override
    public Pair<String, String> getAbsoluteIsoPath(long templateId, long dataCenterId) {
        TemplateDataStoreVO templateStoreRef = _tmplStoreDao.findByTemplateZoneDownloadStatus(templateId, dataCenterId, VMTemplateStorageResourceAssoc.Status.DOWNLOADED);
        if (templateStoreRef == null) {
            throw new CloudRuntimeException("Template " + templateId + " has not been completely downloaded to zone " + dataCenterId);
        }
        DataStore store = _dataStoreMgr.getDataStore(templateStoreRef.getDataStoreId(), DataStoreRole.Image);
        String isoPath = store.getUri() + "/" + templateStoreRef.getInstallPath();
        return new Pair<String, String>(isoPath, store.getUri());
    }

    @Override
    public String getSecondaryStorageURL(long zoneId) {
        DataStore secStore = _dataStoreMgr.getImageStoreWithFreeCapacity(zoneId);
        if (secStore == null) {
            return null;
        }

        return secStore.getUri();
    }

    // get the image store where a template in a given zone is downloaded to,
    // just pick one is enough.
    @Override
    public DataStore getImageStore(long zoneId, long tmpltId) {
        TemplateDataStoreVO tmpltStore = _tmplStoreDao.findByTemplateZoneDownloadStatus(tmpltId, zoneId, VMTemplateStorageResourceAssoc.Status.DOWNLOADED);
        if (tmpltStore != null) {
            return _dataStoreMgr.getDataStore(tmpltStore.getDataStoreId(), DataStoreRole.Image);
        }

        return null;
    }

    // get the region wide image store where a template is READY on,
    // just pick one is enough.
    @Override
    public DataStore getImageStore(long tmpltId) {
        TemplateDataStoreVO tmpltStore = _tmplStoreDao.findReadyByTemplate(tmpltId, DataStoreRole.Image);
        if (tmpltStore != null) {
            return _dataStoreMgr.getDataStore(tmpltStore.getDataStoreId(), DataStoreRole.Image);
        }

        return null;
    }

    @Override
    public Long getTemplateSize(VirtualMachineTemplate template, long zoneId) {
        long templateId = template.getId();
        if (_tmplStoreDao.isTemplateMarkedForDirectDownload(templateId)) {
            // check if template is marked for direct download
            return _tmplStoreDao.getReadyBypassedTemplate(templateId).getSize();
        }
        TemplateDataStoreVO templateStoreRef = _tmplStoreDao.findByTemplateZoneDownloadStatus(templateId, zoneId, VMTemplateStorageResourceAssoc.Status.DOWNLOADED);
        if (templateStoreRef == null) {
            // check if it is ready on image cache stores
            templateStoreRef = _tmplStoreDao.findByTemplateZoneStagingDownloadStatus(templateId, zoneId,
                    VMTemplateStorageResourceAssoc.Status.DOWNLOADED);
            if (templateStoreRef == null) {
                throw new CloudRuntimeException(String.format("Template %s has not been completely downloaded to zone %s", template, _dcDao.findById(zoneId)));
            }
        }
        return templateStoreRef.getSize();

    }

    // find image store where this template is located
    @Override
    public List<DataStore> getImageStoreByTemplate(long templateId, Long zoneId) {
        // find all eligible image stores for this zone scope
        List<DataStore> imageStores = _dataStoreMgr.getImageStoresByScope(new ZoneScope(zoneId));
        if (imageStores == null || imageStores.size() == 0) {
            return null;
        }
        List<DataStore> stores = new ArrayList<DataStore>();
        for (DataStore store : imageStores) {
            // check if the template is stored there
            List<TemplateDataStoreVO> storeTmpl = _tmplStoreDao.listByTemplateStore(templateId, store.getId());
            if (storeTmpl != null && storeTmpl.size() > 0) {
                stores.add(store);
            }
        }
        return stores;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ISO_UPDATE, eventDescription = "updating iso", async = false)
    public VMTemplateVO updateTemplate(UpdateIsoCmd cmd) {
        return updateTemplateOrIso(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TEMPLATE_UPDATE, eventDescription = "updating template", async = false)
    public VMTemplateVO updateTemplate(UpdateTemplateCmd cmd) {
        return updateTemplateOrIso(cmd);
    }

    private VMTemplateVO updateTemplateOrIso(BaseUpdateTemplateOrIsoCmd cmd) {
        Long id = cmd.getId();
        String name = cmd.getTemplateName();
        String displayText = cmd.getDisplayText();
        String format = cmd.getFormat();
        Long guestOSId = cmd.getOsTypeId();
        Boolean passwordEnabled = cmd.getPasswordEnabled();
        Boolean sshKeyEnabled = cmd.isSshKeyEnabled();
        Boolean isDynamicallyScalable = cmd.isDynamicallyScalable();
        Boolean isRoutingTemplate = cmd.isRoutingType();
        Boolean bootable = cmd.getBootable();
        Boolean requiresHvm = cmd.getRequiresHvm();
        Integer sortKey = cmd.getSortKey();
        Map details = cmd.getDetails();
        Account account = CallContext.current().getCallingAccount();
        boolean cleanupDetails = cmd.isCleanupDetails();
        Boolean forCks = cmd instanceof UpdateTemplateCmd ? ((UpdateTemplateCmd) cmd).getForCks() : null;
        CPU.CPUArch arch = cmd.getCPUArch();

        // verify that template exists
        VMTemplateVO template = _tmpltDao.findById(id);
        if (template == null || template.getRemoved() != null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("unable to find template/iso with specified id");
            ex.addProxyObject(String.valueOf(id), "templateId");
            throw ex;
        }
        long oldGuestOSId = template.getGuestOSId();

        verifyTemplateId(id);

        // do a permission check
        _accountMgr.checkAccess(account, AccessType.OperateEntry, true, template);
        if (cmd.isRoutingType() != null) {
            if (!_accountService.isRootAdmin(account.getId())) {
                throw new PermissionDeniedException("Parameter isrouting can only be specified by a Root Admin, permission denied");
            }
        }

        // update template type
        TemplateType templateType = null;
        String templateTag = null;
        if (cmd instanceof UpdateTemplateCmd) {
            boolean isAdmin = _accountMgr.isAdmin(account.getId());
            templateType = validateTemplateType(cmd, isAdmin, template.isCrossZones(), template.getHypervisorType());
            if (cmd instanceof UpdateVnfTemplateCmd) {
                VnfTemplateUtils.validateApiCommandParams(cmd, template);
                vnfTemplateManager.updateVnfTemplate(template.getId(), (UpdateVnfTemplateCmd) cmd);
            }
            templateTag = ((UpdateTemplateCmd)cmd).getTemplateTag();
        }

        // update is needed if any of the fields below got filled by the user
        boolean updateNeeded =
                !(name == null &&
                  displayText == null &&
                  format == null &&
                  guestOSId == null &&
                  passwordEnabled == null &&
                  bootable == null &&
                  sshKeyEnabled == null &&
                  requiresHvm == null &&
                  sortKey == null &&
                  isDynamicallyScalable == null &&
                  isRoutingTemplate == null &&
                  templateType == null &&
                  templateTag == null &&
                  forCks == null &&
                  arch == null &&
                  (! cleanupDetails && details == null) //update details in every case except this one
                  );
        if (!updateNeeded) {
            return template;
        }

        template = _tmpltDao.findById(id);

        if (name != null) {
            template.setName(name);
        }

        if (displayText != null) {
            template.setDisplayText(displayText);
        }

        if (sortKey != null) {
            template.setSortKey(sortKey);
        }

        ImageFormat imageFormat = null;
        if (format != null) {
            try {
                imageFormat = ImageFormat.valueOf(format.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new InvalidParameterValueException("Image format: " + format + " is incorrect. Supported formats are " + EnumUtils.listValues(ImageFormat.values()));
            }

            template.setFormat(imageFormat);
        }

        if (guestOSId != null) {
            GuestOSVO guestOS = _guestOSDao.findById(guestOSId);

            if (guestOS == null) {
                throw new InvalidParameterValueException("Please specify a valid guest OS ID.");
            } else {
                template.setGuestOSId(guestOSId);
            }

            if (guestOSId != oldGuestOSId) { // vm guest os type need to be updated if template guest os id changes.
                SearchCriteria<VMInstanceVO> sc = _vmInstanceDao.createSearchCriteria();
                sc.addAnd("templateId", SearchCriteria.Op.EQ, id);
                sc.addAnd("state", SearchCriteria.Op.NEQ, State.Expunging);
                List<VMInstanceVO> vms = _vmInstanceDao.search(sc, null);
                if (vms != null && !vms.isEmpty()) {
                    if (Boolean.FALSE.equals(cmd.getForceUpdateOsType())) {
                        String message = String.format("Updating OS type will update the guest OS configuration " +
                            "for all of the %d Instance(s) deployed with this Template/ISO, which may affect their behavior. " +
                            "To proceed, please set the 'forceupdateostype' parameter to true.", vms.size());
                        throw new InvalidParameterValueException(message);
                    }
                    for (VMInstanceVO vm: vms) {
                        vm.setGuestOSId(guestOSId);
                        _vmInstanceDao.update(vm.getId(), vm);
                    }
                }
            }
        }

        if (passwordEnabled != null) {
            template.setEnablePassword(passwordEnabled);
        }

        if (sshKeyEnabled != null) {
            template.setEnableSshKey(sshKeyEnabled);
        }

        if (bootable != null) {
            template.setBootable(bootable);
        }

        if (requiresHvm != null) {
            template.setRequiresHvm(requiresHvm);
        }

        if (isDynamicallyScalable != null) {
            template.setDynamicallyScalable(isDynamicallyScalable);
        }

        if (arch != null) {
            template.setArch(arch);
        }

        if (isRoutingTemplate != null) {
            if (isRoutingTemplate) {
                template.setTemplateType(TemplateType.ROUTING);
            } else if (templateType != null) {
                template.setTemplateType(templateType);
            } else {
                template.setTemplateType(TemplateType.USER);
            }
        } else if (templateType != null) {
            template.setTemplateType(templateType);
        }
        if (templateTag != null) {
            template.setTemplateTag(org.apache.commons.lang3.StringUtils.trimToNull(templateTag));
        }

        validateDetails(template, details);

        if (cleanupDetails) {
            template.setDetails(null);
            _tmpltDetailsDao.removeDetails(id);
        }
        else if (details != null && !details.isEmpty()) {
            template.setDetails(details);
            _tmpltDao.saveDetails(template);
        }
        if (forCks != null) {
            template.setForCks(forCks);
        }

        _tmpltDao.update(id, template);

        return _tmpltDao.findById(id);
    }

    @Override
    public TemplateType validateTemplateType(BaseCmd cmd, boolean isAdmin, boolean isCrossZones, HypervisorType hypervisorType) {
        if (!(cmd instanceof UpdateTemplateCmd) && !(cmd instanceof RegisterTemplateCmd)) {
            return null;
        }
        TemplateType templateType = null;
        String newType = null;
        Boolean isRoutingType = null;
        if (cmd instanceof UpdateTemplateCmd) {
            newType = ((UpdateTemplateCmd)cmd).getTemplateType();
            isRoutingType = ((UpdateTemplateCmd)cmd).isRoutingType();
        } else if (cmd instanceof RegisterTemplateCmd) {
            newType = ((RegisterTemplateCmd)cmd).getTemplateType();
            isRoutingType = ((RegisterTemplateCmd)cmd).isRoutingType();
        }
        if (newType != null) {
            try {
                templateType = TemplateType.valueOf(newType.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException(String.format("Please specify a valid templatetype: %s",
                        org.apache.commons.lang3.StringUtils.join(",", TemplateType.values())));
            }
        }
        if (templateType != null) {
            if (isRoutingType != null && (TemplateType.ROUTING.equals(templateType) != isRoutingType)) {
                throw new InvalidParameterValueException("Please specify a valid templatetype (consistent with isrouting parameter).");
            } else if ((templateType == TemplateType.SYSTEM || templateType == TemplateType.BUILTIN) && !isCrossZones) {
                throw new InvalidParameterValueException("System and Builtin templates must be cross zone.");
            } else if ((cmd instanceof RegisterVnfTemplateCmd || cmd instanceof UpdateVnfTemplateCmd) && !TemplateType.VNF.equals(templateType)) {
                throw new InvalidParameterValueException("The template type must be VNF for VNF templates, but the actual type is " + templateType);
            }
        } else if (cmd instanceof RegisterTemplateCmd) {
            boolean isRouting = Boolean.TRUE.equals(isRoutingType);
            templateType = (cmd instanceof RegisterVnfTemplateCmd) ? TemplateType.VNF : (isRouting ? TemplateType.ROUTING : TemplateType.USER);
        }
        validateExternalHypervisorTemplateType(hypervisorType, templateType);
        if (templateType != null && !isAdmin && !Arrays.asList(TemplateType.USER, TemplateType.VNF).contains(templateType)) {
            if (cmd instanceof RegisterTemplateCmd) {
                throw new InvalidParameterValueException(String.format("Users can not register template with template type %s.", templateType));
            } else if (cmd instanceof UpdateTemplateCmd) {
                throw new InvalidParameterValueException(String.format("Users can not update template to template type %s.", templateType));
            }
        }
        return templateType;
    }

    protected void validateExternalHypervisorTemplateType(HypervisorType hypervisorType, TemplateType templateType) {
        if (!HypervisorType.External.equals(hypervisorType) ||
                templateType == null || templateType == TemplateType.USER) {
            return;
        }
        throw new InvalidParameterValueException(String.format("Only %s templates supported for %s hypervisor type",
                TemplateType.USER, HypervisorType.External));
    }

    void validateDetails(VMTemplateVO template, Map<String, String> details) {
        if (MapUtils.isEmpty(details)) {
            return;
        }

        String bootMode = details.get(ApiConstants.BootType.UEFI.toString());
        if (bootMode == null) {
            return;
        }
        if (template.isDeployAsIs()) {
            String msg = String.format("Deploy-as-is template %s can not have the UEFI setting. Settings are read directly from the template", template);
            throw new InvalidParameterValueException(msg);
        }
        try {
            String mode = bootMode.trim().toUpperCase();
            ApiConstants.BootMode.valueOf(mode);
            details.put(ApiConstants.BootType.UEFI.toString(), mode);
            return;
        } catch (IllegalArgumentException e) {
            String msg = String.format("Invalid %s: %s specified. Valid values are: %s",
                ApiConstants.BOOT_MODE, bootMode, Arrays.toString(ApiConstants.BootMode.values()));
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }
    }

    void verifyTemplateId(Long id) {
        // Don't allow to modify system template
        if (id.equals(Long.valueOf(1))) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to update template/iso of specified id");
            ex.addProxyObject(String.valueOf(id), "templateId");
            throw ex;
        }
    }

    @Override
    public String getConfigComponentName() {
        return TemplateManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {AllowPublicUserTemplates, TemplatePreloaderPoolSize, ValidateUrlIsResolvableBeforeRegisteringTemplate};
    }

    public List<TemplateAdapter> getTemplateAdapters() {
        return _adapters;
    }

    @Inject
    public void setTemplateAdapters(List<TemplateAdapter> adapters) {
        _adapters = adapters;
    }

    @Override
    public List<DatadiskTO> getTemplateDisksOnImageStore(VirtualMachineTemplate template, DataStoreRole role, String configurationId) {
        long templateId = template.getId();
        TemplateInfo templateObject = _tmplFactory.getTemplate(templateId, role);
        if (templateObject == null) {
            String msg = String.format("Could not find template %s downloaded on store with role %s", template, role.toString());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        return _tmpltSvr.getTemplateDatadisksOnImageStore(templateObject, configurationId);
    }

    @Override
    public VirtualMachineTemplate linkUserDataToTemplate(LinkUserDataToTemplateCmd cmd) {
        Long templateId = cmd.getTemplateId();
        Long isoId = cmd.getIsoId();
        Long userDataId = cmd.getUserdataId();
        UserData.UserDataOverridePolicy overridePolicy = cmd.getUserdataPolicy();
        Account caller = CallContext.current().getCallingAccount();

        if (templateId != null && isoId != null) {
            throw new InvalidParameterValueException("Both template ID and ISO ID are passed, API accepts only one");
        }
        if (templateId == null && isoId == null) {
            throw new InvalidParameterValueException("Atleast one of template ID or ISO ID needs to be passed");
        }

        VMTemplateVO template = null;
        if (templateId != null) {
            template = _tmpltDao.findById(templateId);
        } else {
            template = _tmpltDao.findById(isoId);
        }
        if (template == null) {
            throw new InvalidParameterValueException(String.format("unable to find template/ISO with id %s", templateId == null? isoId : templateId));
        }

        _accountMgr.checkAccess(caller, AccessType.OperateEntry, true, template);

        template.setUserDataId(userDataId);
        if (userDataId != null) {
            template.setUserDataLinkPolicy(overridePolicy);
        } else {
            template.setUserDataLinkPolicy(null);
        }
        _tmpltDao.update(template.getId(), template);

        return _tmpltDao.findById(template.getId());
    }
}
