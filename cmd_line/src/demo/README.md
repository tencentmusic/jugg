# Command-line demo files

- `bin`: command wrapper.
- `libs`: Jugg command-line implementation.
- `demo`: example workflow; adapt each script to the target project.
  - `1_build_base.sh`: build the Gradle baseline and initialize data needed for incremental builds.
  - `2_modify_project.sh`: change project sources to verify that the incremental APK applies the changes.
  - `3_build_incremental_apk.sh`: build an incremental APK from changed files.
  - `4_install_and_launch.sh`: install the APK and launch the app.
