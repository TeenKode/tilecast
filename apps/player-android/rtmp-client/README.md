# RTMP client native build

This module builds the upstream Ant Media RTMP client with Tilecast's pinned
NDK. Media3 uses its original Java API. The published Maven 3.2.0 AAR contains
4 KB ELF LOAD segments and fails the Player's 16 KB release check.

The C and Java sources are copied from
`ant-media/LibRtmp-Client-for-Android` commit
`3af67842f517f9c6bbd22b961878624d0c44b713`:
https://github.com/ant-media/LibRtmp-Client-for-Android/commit/3af67842f517f9c6bbd22b961878624d0c44b713

The local build adds the missing standard `<string.h>` declaration in
`flvmuxer/xiecc_rtmp.c` for current Clang and disables upstream diagnostic output
through a local callback so stream URLs and credentials cannot reach stderr.
The Java API remains unchanged. Both maximum
and common ELF page sizes are 16 KB. The APK check still checks every library
and ZIP alignment. It is not disabled or limited to selected libraries.

The upstream Apache 2.0 license and librtmp LGPL 2.1 notice are retained and
included in the APK under `assets/licenses`. Keep the source revision and
notices together when updating this module. Do not add JitPack or an unpinned
binary as a substitute for this source build.
