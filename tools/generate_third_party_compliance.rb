#!/usr/bin/env ruby

require 'csv'
require 'digest'
require 'fileutils'
require 'json'

ROOT = File.expand_path('..', __dir__)
THIRD_PARTY_DIR = File.join(ROOT, 'third_party')
COMPONENTS_FILE = File.join(THIRD_PARTY_DIR, 'components.csv')
NOTICE_FILE = File.join(ROOT, 'THIRD_PARTY_NOTICES.md')
MODIFICATIONS_FILE = File.join(THIRD_PARTY_DIR, 'MODIFICATIONS.md')
SBOM_FILE = File.join(THIRD_PARTY_DIR, 'sbom', 'jugg-third-party.spdx.json')
HEADERS = %w[name version license copyright license_url download_url modified notes].freeze

LICENSE_SELECTIONS = {
  'JavaParser Core' => ['Apache-2.0', '本发行选择 Apache-2.0。'],
  'juniversalchardet' => ['MPL-1.1', '本发行按上游 POM 选择 MPL-1.1。'],
  'Fast Infoset' => ['Apache-2.0', '本发行选择 Apache-2.0。'],
  'Java Native Access / JNA Platform' => ['Apache-2.0', '本发行选择 Apache-2.0。'],
  'Java Native Access，JetBrains dependency build' => ['Apache-2.0', '本发行选择 Apache-2.0。'],
  'JavaBeans Activation Framework' => ['CDDL-1.1', '本发行选择 CDDL-1.1。']
}.freeze

SPDX_LICENSES = {
  'Apache-2.0' => 'Apache-2.0',
  'Apache-2.0 WITH LLVM-exception' => 'Apache-2.0 WITH LLVM-exception',
  'Apache-2.0、BSD-2-Clause；SQLite 核心 Public Domain' =>
    'Apache-2.0 AND BSD-2-Clause AND LicenseRef-SQLite-Public-Domain',
  'Apache-2.0、SAX License、W3C Software Notice and License' =>
    'Apache-2.0 AND SAX-PD AND W3C-19980720',
  'Apache-2.0，另含静态链接第三方组件' => 'Apache-2.0',
  'Apache-2.0；部分 sevenz 代码来自 Public Domain LZMA SDK' =>
    'Apache-2.0 AND LicenseRef-LZMA-SDK-Public-Domain',
  'BSD License' => 'BSD-3-Clause',
  'BSD-3-Clause' => 'BSD-3-Clause',
  'BSD-3-Clause，并含第三方许可证' => 'BSD-3-Clause',
  'BSD-style AND Public Domain' => 'LicenseRef-kXML2-BSD AND LicenseRef-XmlPull-Public-Domain',
  'CDDL-1.1' => 'CDDL-1.1',
  'EDL-1.0' => 'BSD-3-Clause',
  'EDL-1.0（BSD-3-Clause）' => 'BSD-3-Clause',
  'GPL-2.0-only WITH Classpath-exception-2.0' => 'GPL-2.0-only WITH Classpath-exception-2.0',
  'GPL-2.0-or-later' => 'GPL-2.0-or-later',
  'GPL-3.0-or-later' => 'GPL-3.0-or-later',
  'ISC' => 'ISC',
  'JDOM License（BSD-style）' => 'LicenseRef-JDOM',
  'LGPL-2.1-or-later' => 'LGPL-2.1-or-later',
  'Libpng-2.0' => 'Libpng-2.0',
  'MIT' => 'MIT',
  'MPL-1.1' => 'MPL-1.1',
  'zlib License' => 'Zlib'
}.freeze

LICENSE_REFS = {
  'LicenseRef-SQLite-Public-Domain' =>
    'The SQLite portion is dedicated to the public domain by its authors.',
  'LicenseRef-LZMA-SDK-Public-Domain' =>
    'The LZMA SDK files identified by Apache Commons Compress are public domain.'
}.freeze

def import_components(path)
  rows = CSV.read(path, encoding: 'bom|utf-8')
  raise "Expected 104 component rows, found #{rows.size}" unless rows.size == 104
  raise 'Each component row must contain eight fields' unless rows.all? { |row| row.size == 8 }

  rows.each do |row|
    selection = LICENSE_SELECTIONS[row[0]]
    next unless selection

    row[2] = selection[0]
    notes = row[7]
      .sub('；建议表中保留双许可证表达', '')
      .sub('；建议表中保留三许可证表达', '')
      .sub('；原仅填 Apache-2.0 不完整', '')
    row[7] = "#{selection[1]} #{notes}"
  end

  FileUtils.mkdir_p(THIRD_PARTY_DIR)
  CSV.open(COMPONENTS_FILE, 'wb', write_headers: true, headers: HEADERS) do |csv|
    rows.each { |row| csv << row }
  end
end

def load_components
  table = CSV.read(COMPONENTS_FILE, headers: true, encoding: 'utf-8')
  raise "Expected 104 component rows, found #{table.size}" unless table.size == 104
  raise "Unexpected headers: #{table.headers.inspect}" unless table.headers == HEADERS
  raise 'Component inventory contains blank fields' unless table.all? { |row| HEADERS.all? { |header| !row[header].to_s.empty? } }

  table
end

def write_notice(rows)
  content = [
    '# Third-Party Software Notices',
    '',
    'Jugg is distributed under the repository license. Third-party software remains under its own license; the Jugg license does not replace or narrow those terms.',
    '',
    'The machine-readable inventory is `third_party/components.csv`. Corresponding license texts are in `third_party/licenses/`. The plugin distribution records the exact public source revision and source checksums in `third_party/SOURCE.md` and `third_party/SOURCE_SHA256SUMS`.',
    ''
  ]

  rows.each_with_index do |row, index|
    content.concat([
      "## #{index + 1}. #{row['name']} #{row['version']}",
      '',
      "- License: #{row['license']}",
      "- Copyright: #{row['copyright']}",
      "- License/source reference: #{row['license_url']}",
      "- Download/source: #{row['download_url']}",
      "- Modified by Jugg: #{row['modified']}",
      "- Notes: #{row['notes']}",
      ''
    ])
  end

  File.write(NOTICE_FILE, content.join("\n"), encoding: 'utf-8')
end

def write_modifications(rows)
  modified = rows.select { |row| row['modified'] == '是' }
  content = [
    '# Third-Party Modifications',
    '',
    'The following redistributed third-party components are marked as modified. The descriptions identify the known Jugg changes and the corresponding upstream reference.',
    ''
  ]

  modified.each do |row|
    content.concat([
      "## #{row['name']} #{row['version']}",
      '',
      row['notes'],
      '',
      "Upstream reference: #{row['download_url']}",
      ''
    ])
  end

  File.write(MODIFICATIONS_FILE, content.join("\n"), encoding: 'utf-8')
end

def write_sbom(rows)
  packages = rows.each_with_index.map do |row, index|
    license = SPDX_LICENSES.fetch(row['license'])
    {
      'SPDXID' => format('SPDXRef-Package-%03d', index + 1),
      'name' => row['name'],
      'versionInfo' => row['version'],
      'downloadLocation' => row['download_url'],
      'filesAnalyzed' => false,
      'licenseConcluded' => license,
      'licenseDeclared' => license,
      'copyrightText' => row['copyright'],
      'comment' => row['notes']
    }
  end
  digest = Digest::SHA256.hexdigest(File.read(COMPONENTS_FILE, encoding: 'utf-8'))
  license_refs = LICENSE_REFS.merge(
    'LicenseRef-kXML2-BSD' => File.read(File.join(THIRD_PARTY_DIR, 'licenses', 'kXML2.txt'), encoding: 'utf-8'),
    'LicenseRef-XmlPull-Public-Domain' =>
      'The upstream kXML2 2.3.0 POM designates the org.xmlpull.v1 API package as public domain.',
    'LicenseRef-JDOM' => File.read(File.join(THIRD_PARTY_DIR, 'licenses', 'JDOM.txt'), encoding: 'utf-8')
  )
  sbom = {
    'spdxVersion' => 'SPDX-2.3',
    'dataLicense' => 'CC0-1.0',
    'SPDXID' => 'SPDXRef-DOCUMENT',
    'name' => 'Jugg-third-party-components',
    'documentNamespace' => "https://jugg.sickworm.com/spdx/third-party-components-#{digest}",
    'creationInfo' => {
      'created' => '2026-08-08T00:00:00Z',
      'creators' => ['Tool: tools/generate_third_party_compliance.rb']
    },
    'documentDescribes' => packages.map { |package| package['SPDXID'] },
    'packages' => packages,
    'hasExtractedLicensingInfos' => license_refs.map do |license_id, extracted_text|
      { 'licenseId' => license_id, 'extractedText' => extracted_text }
    end
  }

  FileUtils.mkdir_p(File.dirname(SBOM_FILE))
  File.write(SBOM_FILE, JSON.pretty_generate(sbom) + "\n", encoding: 'utf-8')
end

def write_form_export(rows, path)
  File.open(path, 'wb') do |file|
    file.write("\uFEFF")
    rows.each { |row| file.write(CSV.generate_line(row.fields)) }
  end
end

import_components(ARGV[1]) if ARGV[0] == '--import'
rows = load_components
write_notice(rows)
write_modifications(rows)
write_sbom(rows)
write_form_export(rows, ARGV[1]) if ARGV[0] == '--export-form'
