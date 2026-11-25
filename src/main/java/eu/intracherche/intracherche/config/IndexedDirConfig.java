package eu.intracherche.intracherche.config;

import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.AssertTrue;

public class IndexedDirConfig {



    /** Always required: POSIX / SMB  */
    @NotNull
    private MountType mountType;

    /** Always required: local filesystem path (POSIX) */
    @NotNull
    private String linuxPath;

    /** Optional: Windows path used for rendering */
    private String windowsPath;

    /** Optional: SMB server name */
    private String smbServer;

    /** Optional: SMB share name */
    private String smbShare;

    private String smbUser;

    private String smbPassword;

    private String smbDomain;

    // --- Getters / setters ---


    public boolean isSmb() {
        return mountType == MountType.SMB;
    }

    // SnakeYAML needs a proper getter method to deserialize the YAML
    public MountType getMountType() {
        return mountType;
    }

    public void setMountType(MountType mountType) {
        this.mountType = mountType;
    }

    public String getLinuxPath() {
        return linuxPath;
    }

    public void setLinuxPath(String linuxPath) {
        this.linuxPath = linuxPath;
    }

    public String getWindowsPath() {
        return windowsPath;
    }

    public void setWindowsPath(String windowsPath) {
        this.windowsPath = windowsPath;
    }

    public String getSmbServer() {
        return smbServer;
    }

    public void setSmbServer(String smbServer) {
        this.smbServer = smbServer;
    }

    public String getSmbShare() {
        return smbShare;
    }

    public void setSmbShare(String smbShare) {
        this.smbShare = smbShare;
    }

    public String getSmbUser() { return smbUser;  }

    public void setSmbUser(String smbUser) { this.smbUser = smbUser;  }

    public String getSmbPassword() { return smbPassword;  }

    public void setSmbPassword(String smbPassword) {  this.smbPassword = smbPassword; }

    public String getSmbDomain() { return smbDomain;  }

    public void setSmbDomain(String smbDomain) { this.smbDomain = smbDomain; }


    // --- Validation rules ---

    @AssertTrue(message = "SMB mount requires smbServer and smbShare")
    public boolean isSmbFieldsPresent() {
        if (mountType != MountType.SMB) {
            return smbServer == null && smbShare == null;
        }
        return smbServer != null && smbShare != null;
    }

    @AssertTrue(message = "windows path is only valid for SMB mounts")
    public boolean isWindowsPathValid() {
        if (windowsPath == null) {
            return true;
        }
        return mountType == MountType.SMB;
    }
}


