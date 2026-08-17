# Third-Party Compliance Bundle

This directory accompanies every Jugg plugin distribution and records the third-party software included, linked, copied, or otherwise used by that distribution.

- `components.csv`: the 104-component inventory used for the open-source information form. Rows are ordered by license-obligation group, modification status, component name, and version. Its `notes` column is written for legal review and states the usage relationship, distribution form, modification details, and source/license obligations where applicable without repeating the `modified` field.
- `licenses/`: applicable license texts and upstream license references.
- `sources/`: corresponding source archives tracked in the public Jugg source revision.
- `MODIFICATIONS.md`: known modifications to redistributed third-party code or files.
- `sbom/`: SPDX 2.3 software bill of materials.
- `THIRD_PARTY_NOTICES.md`: generated at the repository root and copied here during packaging.

Each plugin distribution includes a generated `SOURCE.md` and `SOURCE_SHA256SUMS` that identify the immutable public source revision and verify its third-party source files. The source payload itself is not duplicated in the plugin archive.

The Jugg repository license applies only to Jugg-owned code. Third-party components remain governed by their respective licenses. The immutable public source revision identified by each plugin distribution contains the corresponding source archives and modification records.

Regenerate the notice, modification record, and SBOM after editing `components.csv`:

```shell
ruby tools/generate_third_party_compliance.rb
```
