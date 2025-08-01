/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.hypervisor.kvm.storage;

import com.cloud.exception.InternalErrorException;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.LibvirtDomainXMLParser;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef;
import com.cloud.storage.template.TemplateConstants;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImageOptions;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(MockitoJUnitRunner.class)
public class KVMStorageProcessorTest {

    @Mock
    KVMStoragePoolManager storagePoolManager;

    LibvirtComputingResource resource = Mockito.mock(LibvirtComputingResource.class);

    @InjectMocks
    private KVMStorageProcessor storageProcessor;

    @Spy
    KVMStorageProcessor storageProcessorSpy = new KVMStorageProcessor(storagePoolManager, resource);

    @Mock
    Pair<String, Set<String>> diskToSnapshotAndDisksToAvoidMock;

    @Mock
    Domain domainMock;

    @Mock
    KVMStoragePool kvmStoragePoolMock;

    @Mock
    VolumeObjectTO volumeObjectToMock;

    @Mock
    SnapshotObjectTO snapshotObjectToMock;

    @Mock
    Connect connectMock;

    @Mock
    QemuImg qemuImgMock;

    @Mock
    LibvirtDomainXMLParser libvirtDomainXMLParserMock;
    @Mock
    LibvirtVMDef.DiskDef diskDefMock;

    @Mock
    Logger loggerMock;


    private static final String directDownloadTemporaryPath = "/var/lib/libvirt/images/dd";
    private static final long templateSize = 80000L;

    private AutoCloseable closeable;

    @Before
    public void setUp() throws ConfigurationException {
        closeable = MockitoAnnotations.openMocks(this);
        storageProcessor = new KVMStorageProcessor(storagePoolManager, resource);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    @Test
    public void testIsEnoughSpaceForDownloadTemplateOnTemporaryLocationAssumeEnoughSpaceWhenNotProvided() {
        boolean result = storageProcessor.isEnoughSpaceForDownloadTemplateOnTemporaryLocation(null);
        Assert.assertTrue(result);
    }

    @Test
    public void testIsEnoughSpaceForDownloadTemplateOnTemporaryLocationNotEnoughSpace() {
        try (MockedStatic<Script> ignored = Mockito.mockStatic(Script.class)) {
            Mockito.when(resource.getDirectDownloadTemporaryDownloadPath()).thenReturn(directDownloadTemporaryPath);
            String output = String.valueOf(templateSize - 30000L);
            Mockito.when(Script.runSimpleBashScript(Mockito.anyString())).thenReturn(output);
            boolean result = storageProcessor.isEnoughSpaceForDownloadTemplateOnTemporaryLocation(templateSize);
            Assert.assertFalse(result);
        }
    }

    @Test
    public void testIsEnoughSpaceForDownloadTemplateOnTemporaryLocationEnoughSpace() {
        try (MockedStatic<Script> ignored = Mockito.mockStatic(Script.class)) {
            Mockito.when(resource.getDirectDownloadTemporaryDownloadPath()).thenReturn(directDownloadTemporaryPath);
            String output = String.valueOf(templateSize + 30000L);
            Mockito.when(Script.runSimpleBashScript(Mockito.anyString())).thenReturn(output);
            boolean result = storageProcessor.isEnoughSpaceForDownloadTemplateOnTemporaryLocation(templateSize);
            Assert.assertTrue(result);
        }
    }

    @Test
    public void testIsEnoughSpaceForDownloadTemplateOnTemporaryLocationNotExistingLocation() {
        try (MockedStatic<Script> ignored = Mockito.mockStatic(Script.class)) {
            Mockito.when(resource.getDirectDownloadTemporaryDownloadPath()).thenReturn(directDownloadTemporaryPath);
            String output = String.format("df: ‘%s’: No such file or directory", directDownloadTemporaryPath);
            Mockito.when(Script.runSimpleBashScript(Mockito.anyString())).thenReturn(output);
            boolean result = storageProcessor.isEnoughSpaceForDownloadTemplateOnTemporaryLocation(templateSize);
            Assert.assertFalse(result);
        }
    }

    @Test
    public void validateGetSnapshotPathInPrimaryStorage(){
        String path = "/path/to/disk";
        String snapshotName = "snapshot";
        String expectedResult = String.format("%s%s%s%s%s", path, File.separator, TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR, File.separator, snapshotName);

        String result = storageProcessor.getSnapshotOrCheckpointPathInPrimaryStorage(path, snapshotName, false);
        Assert.assertEquals(expectedResult, result);
    }

    @Test (expected = CloudRuntimeException.class)
    public void validateValidateAvailableSizeOnPoolToTakeVolumeSnapshotAvailabeSizeLessThanMinRateThrowCloudRuntimeException(){
        KVMPhysicalDisk kvmPhysicalDiskMock = Mockito.mock(KVMPhysicalDisk.class);

        Mockito.doReturn(104l).when(kvmStoragePoolMock).getAvailable();
        Mockito.doReturn(100l).when(kvmPhysicalDiskMock).getSize();

        storageProcessor.validateAvailableSizeOnPoolToTakeVolumeSnapshot(kvmStoragePoolMock, kvmPhysicalDiskMock);
    }

    @Test
    public void validateValidateAvailableSizeOnPoolToTakeVolumeSnapshotAvailabeSizeEqualOrHigherThanMinRateDoNothing(){
        KVMPhysicalDisk kvmPhysicalDiskMock = Mockito.mock(KVMPhysicalDisk.class);

        Mockito.doReturn(105l, 106l).when(kvmStoragePoolMock).getAvailable();
        Mockito.doReturn(100l).when(kvmPhysicalDiskMock).getSize();

        storageProcessor.validateAvailableSizeOnPoolToTakeVolumeSnapshot(kvmStoragePoolMock, kvmPhysicalDiskMock);
        storageProcessor.validateAvailableSizeOnPoolToTakeVolumeSnapshot(kvmStoragePoolMock, kvmPhysicalDiskMock);
    }

    private List<LibvirtVMDef.DiskDef> createDiskDefs(int iterations, boolean duplicatePath) {
        List<LibvirtVMDef.DiskDef> disks = new ArrayList<>();

        for (int i = 1; i <= iterations; i++) {
            LibvirtVMDef.DiskDef disk = new LibvirtVMDef.DiskDef();
            disk.defFileBasedDisk(String.format("path%s", duplicatePath ? "" : i), String.format("label%s", i), LibvirtVMDef.DiskDef.DiskBus.USB, LibvirtVMDef.DiskDef.DiskFmtType.RAW);
            disks.add(disk);
        }

        return disks;
    }

    @Test (expected = CloudRuntimeException.class)
    public void validateGetDiskToSnapshotAndDisksToAvoidDuplicatePathThrowsCloudRuntimeException() throws LibvirtException{
        List<LibvirtVMDef.DiskDef> disks = createDiskDefs(2, true);

        storageProcessor.getDiskToSnapshotAndDisksToAvoid(disks, "path", domainMock);
    }

    @Test (expected = CloudRuntimeException.class)
    public void validateGetDiskToSnapshotAndDisksToAvoidPathNotFoundThrowsCloudRuntimeException() throws LibvirtException{
        List<LibvirtVMDef.DiskDef> disks = createDiskDefs(5, false);

        storageProcessor.getDiskToSnapshotAndDisksToAvoid(disks, "path6", domainMock);
    }

    @Test
    public void validateGetDiskToSnapshotAndDisksToAvoidPathFoundReturnLabels() throws LibvirtException{
        List<LibvirtVMDef.DiskDef> disks = createDiskDefs(5, false);

        String expectedLabelResult = "label2";
        long expectedDisksSizeResult = disks.size() - 1;

        Pair<String, Set<String>> result = storageProcessor.getDiskToSnapshotAndDisksToAvoid(disks, "path2", domainMock);

        Assert.assertEquals(expectedLabelResult, result.first());
        Assert.assertEquals(expectedDisksSizeResult, result.second().size());
    }

    @Test (expected = LibvirtException.class)
    public void validateTakeVolumeSnapshotFailToCreateSnapshotThrowLibvirtException() throws LibvirtException{
        Mockito.doReturn(diskToSnapshotAndDisksToAvoidMock).when(storageProcessorSpy).getDiskToSnapshotAndDisksToAvoid(Mockito.any(), Mockito.anyString(), Mockito.any());
        Mockito.doReturn(new HashSet<>()).when(diskToSnapshotAndDisksToAvoidMock).second();
        Mockito.doReturn("").when(resource).getSnapshotTemporaryPath(Mockito.anyString(), Mockito.anyString());
        Mockito.doThrow(LibvirtException.class).when(domainMock).snapshotCreateXML(Mockito.anyString(), Mockito.anyInt());

        storageProcessorSpy.takeVolumeSnapshot(new ArrayList<>(), "", "", domainMock);
    }

    @Test
    public void validateTakeVolumeSnapshotSuccessReturnDiskLabel() throws LibvirtException{
        String expectedResult = "label";

        Mockito.doReturn(diskToSnapshotAndDisksToAvoidMock).when(storageProcessorSpy).getDiskToSnapshotAndDisksToAvoid(Mockito.any(), Mockito.anyString(), Mockito.any());
        Mockito.doReturn(expectedResult).when(diskToSnapshotAndDisksToAvoidMock).first();
        Mockito.doReturn(new HashSet<>()).when(diskToSnapshotAndDisksToAvoidMock).second();
        Mockito.doReturn("").when(resource).getSnapshotTemporaryPath(Mockito.anyString(), Mockito.anyString());
        Mockito.doReturn(null).when(domainMock).snapshotCreateXML(Mockito.anyString(), Mockito.anyInt());

        String result = storageProcessorSpy.takeVolumeSnapshot(new ArrayList<>(), "", "", domainMock);

        Assert.assertEquals(expectedResult, result);
    }

    @Test
    public void convertBaseFileToSnapshotFileInPrimaryStorageDirTestFailToConvertWithQemuImgExceptionReturnErrorMessage() {
        String errorMessage = "error";
        KVMStoragePool primaryPoolMock = Mockito.mock(KVMStoragePool.class);
        KVMPhysicalDisk baseFileMock = Mockito.mock(KVMPhysicalDisk.class);
        VolumeObjectTO volumeMock = Mockito.mock(VolumeObjectTO.class);

        Mockito.when(baseFileMock.getPath()).thenReturn("/path/to/baseFile");
        Mockito.when(primaryPoolMock.createFolder(Mockito.anyString())).thenReturn(true);
        try (MockedConstruction<Script> scr = Mockito.mockConstruction(Script.class, ((mock, context) -> {
            Mockito.doReturn("").when(mock).execute();
        }));
             MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class, ((mock, context) -> {
                     Mockito.lenient().doThrow(new QemuImgException(errorMessage)).when(mock).convert(Mockito.any(QemuImgFile.class), Mockito.any(QemuImgFile.class), Mockito.any(Map.class),
                             Mockito.any(List.class), Mockito.any(QemuImageOptions.class),Mockito.nullable(String.class), Mockito.any(Boolean.class));
             }))) {
            String test = storageProcessor.convertBaseFileToSnapshotFileInStorageDir(primaryPoolMock, baseFileMock, "/path/to/snapshot", TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR, volumeMock, 0);
            Assert.assertNotNull(test);
        }
    }

    @Test
    public void convertBaseFileToSnapshotFileInPrimaryStorageDirTestFailToConvertWithLibvirtExceptionReturnErrorMessage() {
        KVMPhysicalDisk baseFile = Mockito.mock(KVMPhysicalDisk.class);
        String snapshotPath = "snapshotPath";

        Mockito.doReturn(true).when(kvmStoragePoolMock).createFolder(Mockito.anyString());
        try (MockedConstruction<QemuImg> ignored = Mockito.mockConstructionWithAnswer(QemuImg.class, invocation -> {
            throw Mockito.mock(LibvirtException.class);
        })) {
            String result = storageProcessorSpy.convertBaseFileToSnapshotFileInStorageDir(kvmStoragePoolMock, baseFile, snapshotPath, TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR, volumeObjectToMock, 1);
            Assert.assertNotNull(result);
        }
    }

    @Test
    public void convertBaseFileToSnapshotFileInPrimaryStorageDirTestConvertSuccessReturnNull() throws Exception {
        KVMPhysicalDisk baseFile = Mockito.mock(KVMPhysicalDisk.class);
        String snapshotPath = "snapshotPath";

        Mockito.doReturn(true).when(kvmStoragePoolMock).createFolder(Mockito.anyString());
        try (MockedConstruction<QemuImg> ignored = Mockito.mockConstruction(QemuImg.class, (mock, context) -> {
            Mockito.doNothing().when(mock).convert(Mockito.any(QemuImgFile.class), Mockito.any(QemuImgFile.class));
        })) {
            String result = storageProcessorSpy.convertBaseFileToSnapshotFileInStorageDir(kvmStoragePoolMock, baseFile, snapshotPath, TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR, volumeObjectToMock, 1);
            Assert.assertNull(result);
        }
    }

    @Test
    public void validateIsAvailablePoolSizeDividedByDiskSizeLesserThanMinRate(){
        Assert.assertTrue(storageProcessorSpy.isAvailablePoolSizeDividedByDiskSizeLesserThanMinRate(10499l, 10000l));
        Assert.assertFalse(storageProcessorSpy.isAvailablePoolSizeDividedByDiskSizeLesserThanMinRate(10500l, 10000l));
        Assert.assertFalse(storageProcessorSpy.isAvailablePoolSizeDividedByDiskSizeLesserThanMinRate(10501l, 10000l));
    }

    @Test
    public void validateValidateCopyResultResultIsNullReturn() throws CloudRuntimeException, IOException{
        storageProcessorSpy.validateConvertResult(null, "");
    }

    @Test (expected = IOException.class)
    public void validateValidateCopyResultFailToDeleteThrowIOException() throws CloudRuntimeException, IOException{
        try (MockedStatic<Files> ignored = Mockito.mockStatic(Files.class)) {
            Mockito.when(Files.deleteIfExists(Mockito.any())).thenThrow(new IOException(""));
            storageProcessorSpy.validateConvertResult("", "");
        }
    }

    @Test (expected = CloudRuntimeException.class)
    public void validateValidateCopyResulResultNotNullThrowCloudRuntimeException() throws CloudRuntimeException, IOException{
        try (MockedStatic<Files> ignored = Mockito.mockStatic(Files.class)) {
            Mockito.when(Files.deleteIfExists(Mockito.any())).thenReturn(true);
            storageProcessorSpy.validateConvertResult("", "");
        }
    }

    @Test (expected = CloudRuntimeException.class)
    public void validateDeleteSnapshotFileErrorOnDeleteThrowsCloudRuntimeException() throws Exception {
        Mockito.doReturn("").when(snapshotObjectToMock).getPath();
        try (MockedStatic<Files> ignored = Mockito.mockStatic(Files.class)) {
            Mockito.when(Files.deleteIfExists(Mockito.any(Path.class))).thenThrow(IOException.class);

            storageProcessorSpy.deleteSnapshotFile(snapshotObjectToMock);
        }
    }

    @Test
    public void validateDeleteSnapshotFileSuccess () throws IOException {
        Mockito.doReturn("").when(snapshotObjectToMock).getPath();
        try (MockedStatic<Files> filesMockedStatic = Mockito.mockStatic(Files.class)) {
            Mockito.when(Files.deleteIfExists(Mockito.any(Path.class))).thenReturn(true);

            storageProcessorSpy.deleteSnapshotFile(snapshotObjectToMock);

            filesMockedStatic.verify(() -> Files.deleteIfExists(Mockito.any(Path.class)), Mockito.times(1));
        }
    }

    @Test
    public void checkDetachSucessTestDetachReturnTrue() throws Exception {

        List<LibvirtVMDef.DiskDef> disks = createDiskDefs(2, false);
        Mockito.when(domainMock.getXMLDesc(Mockito.anyInt())).thenReturn("test");
        try (MockedConstruction<LibvirtDomainXMLParser> ignored = Mockito.mockConstruction(
                LibvirtDomainXMLParser.class, (mock, context) -> {
                    Mockito.when(mock.parseDomainXML(Mockito.anyString())).thenReturn(true);
                    Mockito.when(mock.getDisks()).thenReturn(disks);
                })) {
            Assert.assertTrue(storageProcessorSpy.checkDetachSuccess("path", domainMock));
        }
    }

    @Test
    public void checkDetachSucessTestDetachReturnFalse() throws Exception {
        List<LibvirtVMDef.DiskDef> disks = createDiskDefs(2, true);
        Mockito.when(domainMock.getXMLDesc(Mockito.anyInt())).thenReturn("test");
        try (MockedConstruction<LibvirtDomainXMLParser> ignored = Mockito.mockConstruction(
                LibvirtDomainXMLParser.class, (mock, context) -> {
                    Mockito.when(mock.parseDomainXML(Mockito.anyString())).thenReturn(true);
                    Mockito.when(mock.getDisks()).thenReturn(disks);
                })) {

            Assert.assertFalse(storageProcessorSpy.checkDetachSuccess("path", domainMock));
        }
    }

    private void attachOrDetachDeviceTest (boolean attach, String vmName, LibvirtVMDef.DiskDef xml) throws LibvirtException, InternalErrorException {
        storageProcessorSpy.attachOrDetachDevice(connectMock, attach, vmName, xml);
    }
    private void attachOrDetachDeviceTest (boolean attach, String vmName, LibvirtVMDef.DiskDef xml, long waitDetachDevice) throws LibvirtException, InternalErrorException {
        storageProcessorSpy.attachOrDetachDevice(connectMock, attach, vmName, xml, waitDetachDevice);
    }

    @Test (expected = LibvirtException.class)
    public void attachOrDetachDeviceTestThrowLibvirtException() throws LibvirtException, InternalErrorException {
        Mockito.when(connectMock.domainLookupByName(Mockito.anyString())).thenThrow(LibvirtException.class);
        attachOrDetachDeviceTest(true, "vmName", diskDefMock);
    }

    @Test
    public void attachOrDetachDeviceTestAttachSuccess() throws LibvirtException, InternalErrorException {
        Mockito.when(connectMock.domainLookupByName("vmName")).thenReturn(domainMock);
        attachOrDetachDeviceTest(true, "vmName", diskDefMock);
        Mockito.verify(domainMock, Mockito.times(1)).attachDevice(Mockito.anyString());
    }

    @Test (expected = LibvirtException.class)
    public void attachOrDetachDeviceTestAttachThrowLibvirtException() throws LibvirtException, InternalErrorException {
        Mockito.when(connectMock.domainLookupByName("vmName")).thenReturn(domainMock);
        Mockito.when(diskDefMock.toString()).thenReturn("diskDef");
        Mockito.when(diskDefMock.getDiskPath()).thenReturn("diskDef");
        Mockito.doThrow(LibvirtException.class).when(domainMock).attachDevice(Mockito.anyString());
        attachOrDetachDeviceTest(true, "vmName", diskDefMock);
    }

    @Test (expected = LibvirtException.class)
    public void attachOrDetachDeviceTestDetachThrowLibvirtException() throws LibvirtException, InternalErrorException {
        Mockito.when(connectMock.domainLookupByName("vmName")).thenReturn(domainMock);
        Mockito.doThrow(LibvirtException.class).when(domainMock).detachDevice(Mockito.anyString());
        attachOrDetachDeviceTest(false, "vmName", diskDefMock);
    }

    @Test
    public void attachOrDetachDeviceTestDetachSuccess() throws LibvirtException, InternalErrorException {
        Mockito.when(connectMock.domainLookupByName("vmName")).thenReturn(domainMock);
        Mockito.doReturn(true).when(storageProcessorSpy).checkDetachSuccess(Mockito.anyString(), Mockito.any(Domain.class));
        Mockito.when(diskDefMock.toString()).thenReturn("diskDef");
        Mockito.when(diskDefMock.getDiskPath()).thenReturn("diskDef");
        attachOrDetachDeviceTest( false, "vmName", diskDefMock, 10000);
        Mockito.verify(domainMock, Mockito.times(1)).detachDevice(Mockito.anyString());
    }

    @Test (expected = InternalErrorException.class)
    public void attachOrDetachDeviceTestDetachThrowInternalErrorException() throws LibvirtException, InternalErrorException {
        Mockito.when(connectMock.domainLookupByName("vmName")).thenReturn(domainMock);
        Mockito.doReturn(false).when(storageProcessorSpy).checkDetachSuccess(Mockito.anyString(), Mockito.any(Domain.class));
        Mockito.when(diskDefMock.toString()).thenReturn("diskDef");
        Mockito.when(diskDefMock.getDiskPath()).thenReturn("diskDef");
        attachOrDetachDeviceTest( false, "vmName", diskDefMock);
        Mockito.verify(domainMock, Mockito.times(1)).detachDevice(Mockito.anyString());
    }

    @Test
    public void generateBackupXmlTestNoParents() {
        String result = storageProcessorSpy.generateBackupXml(null, null, "vda", "path");

        Assert.assertFalse(result.contains("<incremental>"));
    }

    @Test
    public void generateBackupXmlTestWithParents() {
        String result = storageProcessorSpy.generateBackupXml(null, new String[]{"checkpointname"}, "vda", "path");

        Assert.assertTrue(result.contains("<incremental>checkpointname</incremental>"));
    }

    @Test
    public void createFolderOnCorrectStorageTestSecondaryIsNull() {
        storageProcessorSpy.createFolderOnCorrectStorage(kvmStoragePoolMock, null, new Pair<>("t", "u"));

        Mockito.verify(kvmStoragePoolMock).createFolder("u");
    }

    @Test
    public void createFolderOnCorrectStorageTestSecondaryIsNotNull() {
        KVMStoragePool secondaryStoragePoolMock = Mockito.mock(KVMStoragePool.class);

        storageProcessorSpy.createFolderOnCorrectStorage(kvmStoragePoolMock, secondaryStoragePoolMock, new Pair<>("t", "u"));

        Mockito.verify(secondaryStoragePoolMock).createFolder("u");
    }

    @Test (expected = CloudRuntimeException.class)
    public void getDiskLabelToSnapshotTestNoDisks() throws LibvirtException {
        storageProcessorSpy.getDiskLabelToSnapshot(new ArrayList<>(), null, Mockito.mock(Domain.class));
    }

    @Test (expected = CloudRuntimeException.class)
    public void getDiskLabelToSnapshotTestDiskHasNoPath() throws LibvirtException {
        LibvirtVMDef.DiskDef diskDefMock1 = Mockito.mock(LibvirtVMDef.DiskDef.class);
        Mockito.doReturn(null).when(diskDefMock1).getDiskPath();

        storageProcessorSpy.getDiskLabelToSnapshot(List.of(diskDefMock1), "Path", Mockito.mock(Domain.class));
    }

    @Test (expected = CloudRuntimeException.class)
    public void getDiskLabelToSnapshotTestDiskPathDoesNotMatch() throws LibvirtException {
        LibvirtVMDef.DiskDef diskDefMock1 = Mockito.mock(LibvirtVMDef.DiskDef.class);
        Mockito.doReturn("test").when(diskDefMock1).getDiskPath();

        storageProcessorSpy.getDiskLabelToSnapshot(List.of(diskDefMock1), "Path", Mockito.mock(Domain.class));
    }

    @Test
    public void getDiskLabelToSnapshotTestDiskMatches() throws LibvirtException {
        LibvirtVMDef.DiskDef diskDefMock1 = Mockito.mock(LibvirtVMDef.DiskDef.class);
        Mockito.doReturn("Path").when(diskDefMock1).getDiskPath();
        Mockito.doReturn("vda").when(diskDefMock1).getDiskLabel();

        String result = storageProcessorSpy.getDiskLabelToSnapshot(List.of(diskDefMock1), "Path", Mockito.mock(Domain.class));

        Assert.assertEquals("vda", result);
    }
}
