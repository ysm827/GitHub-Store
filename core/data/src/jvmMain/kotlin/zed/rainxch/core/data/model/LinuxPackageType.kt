package zed.rainxch.core.data.model

enum class LinuxPackageType {
    DEB, // Debian/Ubuntu/Mint/Pop/Elementary
    RPM, // Fedora/RHEL/CentOS/openSUSE/Rocky/Alma
    ARCH, // Arch/Manjaro/EndeavourOS/Artix/CachyOS/Garuda
    UNIVERSAL, // Unknown - show AppImage only
}
