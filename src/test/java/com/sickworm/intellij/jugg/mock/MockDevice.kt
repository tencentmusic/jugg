package com.sickworm.intellij.jugg.mock

import com.android.ddmlib.*
import com.android.ddmlib.log.LogReceiver
import com.android.sdklib.AndroidVersion
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class MockDevice: IDevice {

    override fun getName(): String {
        TODO("Not yet implemented")
    }

    override fun executeShellCommand(command: String?, receiver: IShellOutputReceiver?, maxTimeToOutputResponse: Int) {
        TODO("Not yet implemented")
    }

    override fun executeShellCommand(command: String?, receiver: IShellOutputReceiver?) {
        TODO("Not yet implemented")
    }

    override fun executeShellCommand(
        command: String?,
        receiver: IShellOutputReceiver?,
        maxTimeToOutputResponse: Long,
        maxTimeUnits: TimeUnit?
    ) {
        TODO("Not yet implemented")
    }

    override fun executeShellCommand(
        command: String?,
        receiver: IShellOutputReceiver?,
        maxTimeout: Long,
        maxTimeToOutputResponse: Long,
        maxTimeUnits: TimeUnit?
    ) {
        TODO("Not yet implemented")
    }

    override fun getSystemProperty(name: String?): Future<String> {
        TODO("Not yet implemented")
    }

    override fun getSerialNumber(): String {
        TODO("Not yet implemented")
    }

    override fun getAvdName(): String {
        TODO("Not yet implemented")
    }

    override fun getState(): IDevice.DeviceState {
        TODO("Not yet implemented")
    }

    override fun getProperties(): MutableMap<String, String> {
        TODO("Not yet implemented")
    }

    override fun getPropertyCount(): Int {
        TODO("Not yet implemented")
    }

    override fun getProperty(name: String?): String {
        TODO("Not yet implemented")
    }

    override fun arePropertiesSet(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getPropertySync(name: String?): String {
        TODO("Not yet implemented")
    }

    override fun getPropertyCacheOrSync(name: String?): String {
        TODO("Not yet implemented")
    }

    override fun supportsFeature(feature: IDevice.Feature?): Boolean {
        TODO("Not yet implemented")
    }

    override fun supportsFeature(feature: IDevice.HardwareFeature?): Boolean {
        TODO("Not yet implemented")
    }

    override fun getMountPoint(name: String?): String {
        TODO("Not yet implemented")
    }

    override fun isOnline(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isEmulator(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isOffline(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isBootLoader(): Boolean {
        TODO("Not yet implemented")
    }

    override fun hasClients(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getClients(): Array<Client> {
        TODO("Not yet implemented")
    }

    override fun getClient(applicationName: String?): Client {
        TODO("Not yet implemented")
    }

    override fun getSyncService(): SyncService {
        TODO("Not yet implemented")
    }

    override fun getFileListingService(): FileListingService {
        TODO("Not yet implemented")
    }

    override fun getScreenshot(): RawImage {
        TODO("Not yet implemented")
    }

    override fun getScreenshot(timeout: Long, unit: TimeUnit?): RawImage {
        TODO("Not yet implemented")
    }

    override fun startScreenRecorder(
        remoteFilePath: String?,
        options: ScreenRecorderOptions?,
        receiver: IShellOutputReceiver?
    ) {
        TODO("Not yet implemented")
    }

    override fun runEventLogService(receiver: LogReceiver?) {
        TODO("Not yet implemented")
    }

    override fun runLogService(logname: String?, receiver: LogReceiver?) {
        TODO("Not yet implemented")
    }

    override fun createForward(localPort: Int, remotePort: Int) {
        TODO("Not yet implemented")
    }

    override fun createForward(
        localPort: Int,
        remoteSocketName: String?,
        namespace: IDevice.DeviceUnixSocketNamespace?
    ) {
        TODO("Not yet implemented")
    }

    override fun removeForward(localPort: Int, remotePort: Int) {
        TODO("Not yet implemented")
    }

    override fun removeForward(
        localPort: Int,
        remoteSocketName: String?,
        namespace: IDevice.DeviceUnixSocketNamespace?
    ) {
        TODO("Not yet implemented")
    }

    override fun getClientName(pid: Int): String {
        TODO("Not yet implemented")
    }

    override fun pushFile(local: String?, remote: String?) {
        TODO("Not yet implemented")
    }

    override fun pullFile(remote: String?, local: String?) {
        TODO("Not yet implemented")
    }

    override fun installPackage(packageFilePath: String?, reinstall: Boolean, vararg extraArgs: String?) {
        TODO("Not yet implemented")
    }

    override fun installPackage(
        packageFilePath: String?,
        reinstall: Boolean,
        receiver: InstallReceiver?,
        vararg extraArgs: String?
    ) {
        TODO("Not yet implemented")
    }

    override fun installPackage(
        packageFilePath: String?,
        reinstall: Boolean,
        receiver: InstallReceiver?,
        maxTimeout: Long,
        maxTimeToOutputResponse: Long,
        maxTimeUnits: TimeUnit?,
        vararg extraArgs: String?
    ) {
        TODO("Not yet implemented")
    }

    override fun installPackages(
        apks: MutableList<File>?,
        reinstall: Boolean,
        installOptions: MutableList<String>?,
        timeout: Long,
        timeoutUnit: TimeUnit?
    ) {
        TODO("Not yet implemented")
    }

    override fun syncPackageToDevice(localFilePath: String?): String {
        TODO("Not yet implemented")
    }

    override fun installRemotePackage(remoteFilePath: String?, reinstall: Boolean, vararg extraArgs: String?) {
        TODO("Not yet implemented")
    }

    override fun installRemotePackage(
        remoteFilePath: String?,
        reinstall: Boolean,
        receiver: InstallReceiver?,
        vararg extraArgs: String?
    ) {
        TODO("Not yet implemented")
    }

    override fun installRemotePackage(
        remoteFilePath: String?,
        reinstall: Boolean,
        receiver: InstallReceiver?,
        maxTimeout: Long,
        maxTimeToOutputResponse: Long,
        maxTimeUnits: TimeUnit?,
        vararg extraArgs: String?
    ) {
        TODO("Not yet implemented")
    }

    override fun removeRemotePackage(remoteFilePath: String?) {
        TODO("Not yet implemented")
    }

    override fun uninstallPackage(packageName: String?): String {
        TODO("Not yet implemented")
    }

    override fun reboot(into: String?) {
        TODO("Not yet implemented")
    }

    override fun root(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isRoot(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getBatteryLevel(): Int {
        TODO("Not yet implemented")
    }

    override fun getBatteryLevel(freshnessMs: Long): Int {
        TODO("Not yet implemented")
    }

    override fun getBattery(): Future<Int> {
        TODO("Not yet implemented")
    }

    override fun getBattery(freshnessTime: Long, timeUnit: TimeUnit?): Future<Int> {
        TODO("Not yet implemented")
    }

    override fun getAbis(): MutableList<String> {
        TODO("Not yet implemented")
    }

    override fun getDensity(): Int {
        TODO("Not yet implemented")
    }

    override fun getLanguage(): String {
        TODO("Not yet implemented")
    }

    override fun getRegion(): String {
        TODO("Not yet implemented")
    }

    override fun getVersion(): AndroidVersion {
        TODO("Not yet implemented")
    }
}