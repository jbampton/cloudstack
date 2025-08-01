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
package org.apache.cloudstack.api.response;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponseWithAnnotations;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.ha.HAConfig;
import org.apache.cloudstack.outofbandmanagement.OutOfBandManagement;

import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = Host.class)
public class HostResponse extends BaseResponseWithAnnotations {
    @SerializedName(ApiConstants.ID)
    @Param(description = "the ID of the host")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "the name of the host")
    private String name;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "the state of the host")
    private Status state;

    @SerializedName("disconnected")
    @Param(description = "true if the host is disconnected. False otherwise.")
    private Date disconnectedOn;

    @SerializedName(ApiConstants.TYPE)
    @Param(description = "the host type")
    private Host.Type hostType;

    @SerializedName("oscategoryid")
    @Param(description = "the OS category ID of the host")
    private String osCategoryId;

    @SerializedName("oscategoryname")
    @Param(description = "the OS category name of the host")
    private String osCategoryName;

    @SerializedName(ApiConstants.IP_ADDRESS)
    @Param(description = "the IP address of the host")
    private String ipAddress;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "the Zone ID of the host")
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "the Zone name of the host")
    private String zoneName;

    @SerializedName(ApiConstants.POD_ID)
    @Param(description = "the Pod ID of the host")
    private String podId;

    @SerializedName("podname")
    @Param(description = "the Pod name of the host")
    private String podName;

    @SerializedName("version")
    @Param(description = "the host version")
    private String version;

    @SerializedName(ApiConstants.HYPERVISOR)
    @Param(description = "the host hypervisor")
    private String hypervisor;
    @SerializedName("cpusockets")
    @Param(description = "the number of CPU sockets on the host")
    private Integer cpuSockets;

    @SerializedName("cpunumber")
    @Param(description = "the CPU number of the host")
    private Integer cpuNumber;

    @SerializedName("cpuspeed")
    @Param(description = "the CPU speed of the host")
    private Long cpuSpeed;

    @Deprecated
    @SerializedName("cpuallocated")
    @Param(description = "the amount of the host's CPU currently allocated")
    private String cpuAllocated;

    @SerializedName("cpuallocatedvalue")
    @Param(description = "the amount of the host's CPU currently allocated in MHz")
    private Long cpuAllocatedValue;

    @SerializedName("cpuallocatedpercentage")
    @Param(description = "the amount of the host's CPU currently allocated in percentage")
    private String cpuAllocatedPercentage;

    @SerializedName("cpuallocatedwithoverprovisioning")
    @Param(description = "the amount of the host's CPU currently allocated after applying the cpu.overprovisioning.factor")
    private String cpuAllocatedWithOverprovisioning;

    @SerializedName("cpuused")
    @Param(description = "the amount of the host's CPU currently used")
    private String cpuUsed;

    @SerializedName("cpuwithoverprovisioning")
    @Param(description = "the amount of the host's CPU after applying the cpu.overprovisioning.factor")
    private String cpuWithOverprovisioning;

    @SerializedName(ApiConstants.CPU_LOAD_AVERAGE)
    @Param(description = "the cpu average load on the host")
    private Double cpuloadaverage;

    @SerializedName("networkkbsread")
    @Param(description = "the incoming network traffic on the host")
    private Long networkKbsRead;

    @SerializedName("networkkbswrite")
    @Param(description = "the outgoing network traffic on the host")
    private Long networkKbsWrite;

    @Deprecated
    @SerializedName("memorytotal")
    @Param(description = "the memory total of the host, this parameter is deprecated use memorywithoverprovisioning")
    private Long memoryTotal;

    @SerializedName("memorywithoverprovisioning")
    @Param(description = "the amount of the host's memory after applying the mem.overprovisioning.factor")
    private String memWithOverprovisioning;

    @Deprecated
    @SerializedName("memoryallocated")
    @Param(description = "the amount of the host's memory currently allocated")
    private long memoryAllocated;

    @SerializedName("memoryallocatedpercentage")
    @Param(description = "the amount of the host's memory currently allocated in percentage")
    private String memoryAllocatedPercentage;

    @SerializedName("memoryallocatedbytes")
    @Param(description = "the amount of the host's memory currently allocated in bytes")
    private Long memoryAllocatedBytes;

    @SerializedName("memoryused")
    @Param(description = "the amount of the host's memory currently used")
    private Long memoryUsed;

    @SerializedName(ApiConstants.GPUGROUP)
    @Param(description = "GPU cards present in the host", responseObject = GpuResponse.class, since = "4.4")
    private List<GpuResponse> gpuGroup;

    @SerializedName("disksizetotal")
    @Param(description = "the total disk size of the host")
    private Long diskSizeTotal;

    @SerializedName("disksizeallocated")
    @Param(description = "the host's currently allocated disk size")
    private Long diskSizeAllocated;

    @SerializedName("capabilities")
    @Param(description = "capabilities of the host")
    private String capabilities;

    @SerializedName("lastpinged")
    @Param(description = "the date and time the host was last pinged")
    private Date lastPinged;

    @SerializedName(ApiConstants.VIRTUAL_MACHINE_ID)
    @Param(description = "the virtual machine id for host type ConsoleProxy and SecondaryStorageVM", since = "4.21.0")
    private String virtualMachineId;

    @SerializedName(ApiConstants.MANAGEMENT_SERVER_ID)
    @Param(description = "the management server ID of the host")
    private String managementServerId;

    @SerializedName(ApiConstants.MANAGEMENT_SERVER_NAME)
    @Param(description = "the management server name of the host", since = "4.21.0")
    private String managementServerName;

    private transient long clusterInternalId;

    @SerializedName("clusterid")
    @Param(description = "the cluster ID of the host")
    private String clusterId;

    @SerializedName("clustername")
    @Param(description = "the cluster name of the host")
    private String clusterName;

    @SerializedName("clustertype")
    @Param(description = "the cluster type of the cluster that host belongs to")
    private String clusterType;

    @SerializedName("islocalstorageactive")
    @Param(description = "true if local storage is active, false otherwise")
    private Boolean localStorageActive;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "the date and time the host was created")
    private Date created;

    @SerializedName("removed")
    @Param(description = "the date and time the host was removed")
    private Date removed;

    @SerializedName("events")
    @Param(description = "events available for the host")
    private String events;

    @SerializedName("hosttags")
    @Param(description = "comma-separated list of tags for the host")
    private String hostTags;

    @SerializedName("explicithosttags")
    @Param(description = "comma-separated list of explicit host tags for the host", since = "4.20.0")
    private String explicitHostTags;

    @SerializedName("implicithosttags")
    @Param(description = "comma-separated list of implicit host tags for the host", since = "4.20.0")
    private String implicitHostTags;

    @SerializedName(ApiConstants.IS_TAG_A_RULE)
    @Param(description = ApiConstants.PARAMETER_DESCRIPTION_IS_TAG_A_RULE)
    private Boolean isTagARule;

    @SerializedName("hasenoughcapacity")
    @Param(description = "true if this host has enough CPU and RAM capacity to migrate a VM to it, false otherwise")
    private Boolean hasEnoughCapacity;

    @SerializedName("suitableformigration")
    @Param(description = "true if this host is suitable(has enough capacity and satisfies all conditions like hosttags, max guests vm limit etc) to migrate a VM to it , false otherwise")
    private Boolean suitableForMigration;

    @SerializedName("hostha")
    @Param(description = "the host HA information information")
    private HostHAResponse hostHAResponse;

    @SerializedName("outofbandmanagement")
    @Param(description = "the host out-of-band management information")
    private OutOfBandManagementResponse outOfBandManagementResponse;

    @SerializedName("resourcestate")
    @Param(description = "the resource state of the host")
    private String resourceState;

    @SerializedName(ApiConstants.HYPERVISOR_VERSION)
    @Param(description = "the hypervisor version")
    private String hypervisorVersion;

    @SerializedName(ApiConstants.HA_HOST)
    @Param(description = "true if the host is Ha host (dedicated to vms started by HA process; false otherwise")
    private Boolean haHost;

    @SerializedName(ApiConstants.DETAILS)
    @Param(description = "Host details in key/value pairs.", since = "4.5")
    private Map details;

    @SerializedName(ApiConstants.ANNOTATION)
    @Param(description = "the last annotation set on this host by an admin", since = "4.11")
    private String annotation;

    @SerializedName(ApiConstants.LAST_ANNOTATED)
    @Param(description = "the last time this host was annotated", since = "4.11")
    private Date lastAnnotated;

    @SerializedName(ApiConstants.USERNAME)
    @Param(description = "the admin that annotated this host", since = "4.11")
    private String username;

    @SerializedName("ueficapability")
    @Param(description = "true if the host has capability to support UEFI boot")
    private Boolean uefiCapability;

    @SerializedName(ApiConstants.ENCRYPTION_SUPPORTED)
    @Param(description = "true if the host supports encryption", since = "4.18")
    private Boolean encryptionSupported;

    @SerializedName(ApiConstants.INSTANCE_CONVERSION_SUPPORTED)
    @Param(description = "true if the host supports instance conversion (using virt-v2v)", since = "4.19.1")
    private Boolean instanceConversionSupported;

    @SerializedName(ApiConstants.ARCH)
    @Param(description = "CPU Arch of the host", since = "4.20")
    private String arch;

    @SerializedName(ApiConstants.STORAGE_ACCESS_GROUPS)
    @Param(description = "comma-separated list of storage access groups for the host", since = "4.21.0")
    private String storageAccessGroups;

    @SerializedName(ApiConstants.CLUSTER_STORAGE_ACCESS_GROUPS)
    @Param(description = "comma-separated list of storage access groups on the cluster", since = "4.21.0")
    private String clusterStorageAccessGroups;

    @SerializedName(ApiConstants.POD_STORAGE_ACCESS_GROUPS)
    @Param(description = "comma-separated list of storage access groups on the pod", since = "4.21.0")
    private String podStorageAccessGroups;

    @SerializedName(ApiConstants.ZONE_STORAGE_ACCESS_GROUPS)
    @Param(description = "comma-separated list of storage access groups on the zone", since = "4.21.0")
    private String zoneStorageAccessGroups;

    @SerializedName(ApiConstants.EXTENSION_ID)
    @Param(description="The ID of extension for this cluster", since = "4.21.0")
    private String extensionId;

    @SerializedName(ApiConstants.EXTENSION_NAME)
    @Param(description="The name of extension for this cluster", since = "4.21.0")
    private String extensionName;

    @Override
    public String getObjectId() {
        return this.getId();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setState(Status state) {
        this.state = state;
    }

    public void setDisconnectedOn(Date disconnectedOn) {
        this.disconnectedOn = disconnectedOn;
    }

    public void setHostType(Host.Type hostType) {
        this.hostType = hostType;
    }

    public void setOsCategoryId(String osCategoryId) {
        this.osCategoryId = osCategoryId;
    }

    public void setOsCategoryName(String osCategoryName) {
        this.osCategoryName = osCategoryName;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public void setPodId(String podId) {
        this.podId = podId;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setHypervisor(String hypervisor) {
        this.hypervisor = hypervisor;
    }

    public void setCpuSockets(Integer cpuSockets) {
        this.cpuSockets = cpuSockets;
    }

    public void setCpuNumber(Integer cpuNumber) {
        this.cpuNumber = cpuNumber;
    }

    public void setCpuSpeed(Long cpuSpeed) {
        this.cpuSpeed = cpuSpeed;
    }

    public void setCpuAllocated(String cpuAllocated) {
        this.cpuAllocated = cpuAllocated;
    }

    public void setCpuAllocatedValue(Long cpuAllocatedValue) {
        this.cpuAllocatedValue = cpuAllocatedValue;
    }

    public void setCpuAllocatedPercentage(String cpuAllocatedPercentage) {
        this.cpuAllocatedPercentage = cpuAllocatedPercentage;
    }

    public void setCpuAllocatedWithOverprovisioning(String cpuAllocatedWithOverprovisioning) {
        this.cpuAllocatedWithOverprovisioning = cpuAllocatedWithOverprovisioning;
    }

    public void setCpuUsed(String cpuUsed) {
        this.cpuUsed = cpuUsed;
    }

    public void setCpuAverageLoad(Double averageLoad) {
        this.cpuloadaverage = averageLoad;
    }

    public void setNetworkKbsRead(Long networkKbsRead) {
        this.networkKbsRead = networkKbsRead;
    }

    public void setNetworkKbsWrite(Long networkKbsWrite) {
        this.networkKbsWrite = networkKbsWrite;
    }

    public void setMemWithOverprovisioning(String memWithOverprovisioning){
        this.memWithOverprovisioning=memWithOverprovisioning;
    }

    public void setMemoryAllocated(long memoryAllocated) {
        this.memoryAllocated = memoryAllocated;
    }

    public void setMemoryUsed(Long memoryUsed) {
        this.memoryUsed = memoryUsed;
    }

    public void setGpuGroup(List<GpuResponse> gpuGroup) {
        this.gpuGroup = gpuGroup;
    }

    public void setDiskSizeTotal(Long diskSizeTotal) {
        this.diskSizeTotal = diskSizeTotal;
    }

    public void setDiskSizeAllocated(Long diskSizeAllocated) {
        this.diskSizeAllocated = diskSizeAllocated;
    }

    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    public void setLastPinged(Date lastPinged) {
        this.lastPinged = lastPinged;
    }

    public void setVirtualMachineId(String virtualMachineId) {
        this.virtualMachineId = virtualMachineId;
    }

    public void setManagementServerId(String managementServerId) {
        this.managementServerId = managementServerId;
    }

    public void setManagementServerName(String managementServerName) {
        this.managementServerName = managementServerName;
    }

    public long getClusterInternalId() {
        return clusterInternalId;
    }

    public void setClusterInternalId(long clusterInternalId) {
        this.clusterInternalId = clusterInternalId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public void setClusterType(String clusterType) {
        this.clusterType = clusterType;
    }

    public void setLocalStorageActive(Boolean localStorageActive) {
        this.localStorageActive = localStorageActive;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public void setRemoved(Date removed) {
        this.removed = removed;
    }

    public void setEvents(String events) {
        this.events = events;
    }

    public String getHostTags() {
        return hostTags;
    }

    public void setHostTags(String hostTags) {
        this.hostTags = hostTags;
    }

    public String getStorageAccessGroups() {
        return storageAccessGroups;
    }

    public void setStorageAccessGroups(String storageAccessGroups) {
        this.storageAccessGroups = storageAccessGroups;
    }

    public String getClusterStorageAccessGroups() {
        return clusterStorageAccessGroups;
    }

    public void setClusterStorageAccessGroups(String clusterStorageAccessGroups) {
        this.clusterStorageAccessGroups = clusterStorageAccessGroups;
    }

    public String getPodStorageAccessGroups() {
        return podStorageAccessGroups;
    }

    public void setPodStorageAccessGroups(String podStorageAccessGroups) {
        this.podStorageAccessGroups = podStorageAccessGroups;
    }

    public String getZoneStorageAccessGroups() {
        return zoneStorageAccessGroups;
    }

    public void setZoneStorageAccessGroups(String zoneStorageAccessGroups) {
        this.zoneStorageAccessGroups = zoneStorageAccessGroups;
    }

    public String getExplicitHostTags() {
        return explicitHostTags;
    }

    public void setExplicitHostTags(String explicitHostTags) {
        this.explicitHostTags = explicitHostTags;
    }

    public String getImplicitHostTags() {
        return implicitHostTags;
    }

    public void setImplicitHostTags(String implicitHostTags) {
        this.implicitHostTags = implicitHostTags;
    }

    public void setHasEnoughCapacity(Boolean hasEnoughCapacity) {
        this.hasEnoughCapacity = hasEnoughCapacity;
    }

    public void setSuitableForMigration(Boolean suitableForMigration) {
        this.suitableForMigration = suitableForMigration;
    }

    public HostHAResponse getHostHAResponse() {
        return hostHAResponse;
    }

    public void setHostHAResponse(final HAConfig config) {
        this.hostHAResponse = new HostHAResponse(config);
    }

    public void setHostHAResponse(HostHAResponse hostHAResponse) {
        this.hostHAResponse = hostHAResponse;
    }

    public OutOfBandManagementResponse getOutOfBandManagementResponse() {
        return outOfBandManagementResponse;
    }

    public void setOutOfBandManagementResponse(final OutOfBandManagement outOfBandManagementConfig) {
        this.outOfBandManagementResponse =  new OutOfBandManagementResponse(outOfBandManagementConfig);
    }

    public void setOutOfBandManagementResponse(OutOfBandManagementResponse outOfBandManagementResponse) {
        this.outOfBandManagementResponse = outOfBandManagementResponse;
    }

    public String getResourceState() {
        return resourceState;
    }

    public void setResourceState(String resourceState) {
        this.resourceState = resourceState;
    }

    public String getCpuWithOverprovisioning() {
        return cpuWithOverprovisioning;
    }

    public void setCpuWithOverprovisioning(String cpuWithOverprovisioning) {
        this.cpuWithOverprovisioning = cpuWithOverprovisioning;
    }

    public void setHypervisorVersion(String hypervisorVersion) {
        this.hypervisorVersion = hypervisorVersion;
    }

    public void setHaHost(Boolean haHost) {
        this.haHost = haHost;
    }

    public void setAnnotation(String annotation) {
        this.annotation = annotation;
    }

    public void setLastAnnotated(Date lastAnnotated) {
        this.lastAnnotated = lastAnnotated;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setDetails(Map details, Hypervisor.HypervisorType hypervisorType) {

        if (details == null) {
            return;
        }

        final Map detailsCopy = new HashMap(details);

        // Fix for CVE ID 2015-3251
        // Remove sensitive host credential information from
        // the details to prevent leakage through API calls
        detailsCopy.remove("username");
        detailsCopy.remove("password");

        if (detailsCopy.containsKey(Host.HOST_VOLUME_ENCRYPTION)) {
            this.setEncryptionSupported(Boolean.parseBoolean((String) detailsCopy.get(Host.HOST_VOLUME_ENCRYPTION)));
            detailsCopy.remove(Host.HOST_VOLUME_ENCRYPTION);
        } else {
            this.setEncryptionSupported(new Boolean(false)); // default
        }

        if (Hypervisor.HypervisorType.KVM.equals(hypervisorType)) {
            if (detailsCopy.containsKey(Host.HOST_INSTANCE_CONVERSION)) {
                this.setInstanceConversionSupported(Boolean.parseBoolean((String) detailsCopy.get(Host.HOST_INSTANCE_CONVERSION)));
                detailsCopy.remove(Host.HOST_INSTANCE_CONVERSION);
            } else {
                this.setInstanceConversionSupported(new Boolean(false)); // default
            }
        }

        this.details = detailsCopy;
    }

    public Map getDetails() {
        return details;
    }

    public void setMemoryTotal(Long memoryTotal) {
        this.memoryTotal = memoryTotal;
    }
    public String getName() {
        return name;
    }

    public Status getState() {
        return state;
    }

    public Date getDisconnectedOn() {
        return disconnectedOn;
    }

    public Host.Type getHostType() {
        return hostType;
    }

    public String getOsCategoryId() {
        return osCategoryId;
    }

    public String getOsCategoryName() {
        return osCategoryName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getZoneId() {
        return zoneId;
    }

    public String getZoneName() {
        return zoneName;
    }

    public String getPodId() {
        return podId;
    }

    public String getPodName() {
        return podName;
    }

    public String getVersion() {
        return version;
    }

    public String getHypervisor() {
        return hypervisor;
    }

    public Integer getCpuSockets() {
        return cpuSockets;
    }

    public Integer getCpuNumber() {
        return cpuNumber;
    }

    public Long getCpuSpeed() {
        return cpuSpeed;
    }

    public String getCpuUsed() {
        return cpuUsed;
    }

    public String getCpuAllocated() {
        return cpuAllocated;
    }

    public Double getAverageLoad() {
        return cpuloadaverage;
    }

    public Long getNetworkKbsRead() {
        return networkKbsRead;
    }

    public Long getNetworkKbsWrite() {
        return networkKbsWrite;
    }

    public Long getMemoryTotal() {
        return memoryTotal;
    }

    public long getMemoryAllocated() {
        return memoryAllocated;
    }

    public void setMemoryAllocatedPercentage(String memoryAllocatedPercentage) {
        this.memoryAllocatedPercentage = memoryAllocatedPercentage;
    }

    public void setMemoryAllocatedBytes(Long memoryAllocatedBytes) {
        this.memoryAllocatedBytes = memoryAllocatedBytes;
    }

    public Long getMemoryUsed() {
        return memoryUsed;
    }

    public List<GpuResponse> getGpuGroup() {
        return gpuGroup;
    }

    public Long getDiskSizeTotal() {
        return diskSizeTotal;
    }

    public Long getDiskSizeAllocated() {
        return diskSizeAllocated;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public Date getLastPinged() {
        return lastPinged;
    }

    public String getVirtualMachineId() {
        return virtualMachineId;
    }

    public String getManagementServerId() {
        return managementServerId;
    }

    public String getManagementServerName() {
        return managementServerName;
    }

    public String getClusterId() {
        return clusterId;
    }

    public String getClusterName() {
        return clusterName;
    }

    public String getClusterType() {
        return clusterType;
    }

    public Boolean getLocalStorageActive() {
        return localStorageActive;
    }

    public Date getCreated() {
        return created;
    }

    public Date getRemoved() {
        return removed;
    }

    public String getEvents() {
        return events;
    }

    public Boolean hasEnoughCapacity() {
        return hasEnoughCapacity;
    }

    public Boolean getSuitableForMigration() {
        return suitableForMigration;
    }

    public String getHypervisorVersion() {
        return hypervisorVersion;
    }

    public Boolean getHaHost() {
        return haHost;
    }

    public void setUefiCapability(Boolean hostCapability) {
        this.uefiCapability = hostCapability;
    }

    public void setEncryptionSupported(Boolean encryptionSupported) {
        this.encryptionSupported = encryptionSupported;
    }

    public void setInstanceConversionSupported(Boolean instanceConversionSupported) {
        this.instanceConversionSupported = instanceConversionSupported;
    }

    public Boolean getIsTagARule() {
        return isTagARule;
    }

    public void setIsTagARule(Boolean tagARule) {
        isTagARule = tagARule;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public String getArch() {
        return arch;
    }

    public Long getCpuAllocatedValue() {
        return cpuAllocatedValue;
    }

    public String getCpuAllocatedPercentage() {
        return cpuAllocatedPercentage;
    }

    public String getCpuAllocatedWithOverprovisioning() {
        return cpuAllocatedWithOverprovisioning;
    }

    public Double getCpuloadaverage() {
        return cpuloadaverage;
    }

    public void setCpuloadaverage(Double cpuloadaverage) {
        this.cpuloadaverage = cpuloadaverage;
    }

    public String getMemWithOverprovisioning() {
        return memWithOverprovisioning;
    }

    public String getMemoryAllocatedPercentage() {
        return memoryAllocatedPercentage;
    }

    public Long getMemoryAllocatedBytes() {
        return memoryAllocatedBytes;
    }

    public Boolean getTagARule() {
        return isTagARule;
    }

    public void setTagARule(Boolean tagARule) {
        isTagARule = tagARule;
    }

    public Boolean getHasEnoughCapacity() {
        return hasEnoughCapacity;
    }

    public void setDetails(Map details) {
        this.details = details;
    }

    public String getAnnotation() {
        return annotation;
    }

    public Date getLastAnnotated() {
        return lastAnnotated;
    }

    public String getUsername() {
        return username;
    }

    public Boolean getUefiCapability() {
        return uefiCapability;
    }

    public Boolean getEncryptionSupported() {
        return encryptionSupported;
    }

    public Boolean getInstanceConversionSupported() {
        return instanceConversionSupported;
    }

    public void setExtensionId(String extensionId) {
        this.extensionId = extensionId;
    }

    public String getExtensionId() {
        return extensionId;
    }

    public void setExtensionName(String extensionName) {
        this.extensionName = extensionName;
    }

    public String getExtensionName() {
        return extensionName;
    }
}
