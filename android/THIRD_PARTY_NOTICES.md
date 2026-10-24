# LAME MP3 encoder

EchoCraft uses LAME 3.100, dynamically linked as `libmp3lame.so`, under the GNU Library General Public License version 2 or later. Copyright belongs to the LAME authors; see the upstream source files for individual notices. The license text is also packaged in the APK at `assets/licenses/LAME-LGPL-2.0.txt`.

Exact unmodified source: https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz

SHA256: `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`

Alternative source archive: https://download.videolan.org/pub/contrib/lame/lame-3.100.tar.gz

The source archive is downloaded at build time with SHA256 verification. It is not committed as a vendored binary. Source changes: none. The Android build configuration is in `app/src/main/cpp/lame-config/config.h`; CMake build instructions and the separate JNI adapter are included in this repository.

To rebuild and replace the library, extract this exact source archive to `android/.deps/lame-3.100`, install the documented Android NDK/CMake toolchain, then run `android/gradlew.bat -p android assembleDebug`. This rebuilds `libmp3lame.so`, links it dynamically with the JNI wrapper, and packages both in the APK. The app does not verify a particular library binary or prohibit replacement for personal use. You may rebuild and sign the APK with your own key. Please retain the LAME license and attribution when redistributing it.

The corresponding source archive will also be supplied with releases that include this library.
