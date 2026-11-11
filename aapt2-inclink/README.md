# AAPT2 incremental linker prebuilts

The platform executables under `src/main/resources/tools/` are tracked with Git LFS. Install Git LFS and run `git lfs install` before cloning; in an existing checkout, run `git lfs pull` before building. Gradle packages the downloaded binary bytes at the existing resource paths. Plugin build workflows enable LFS during checkout. Replacing a prebuilt uses the normal `git add` / `git commit` flow; the LFS pre-push hook uploads its content when pushing.

The conversion preserves existing history. Earlier commits still contain ordinary Git blobs.
