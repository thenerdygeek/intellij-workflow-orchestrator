# Third-Party Notices

This file aggregates the attribution notices required by the licenses of the
third-party software bundled with the Workflow Orchestrator IntelliJ plugin.

The plugin itself is distributed under the Apache License, Version 2.0
(see `LICENSE`). Each third-party component remains under its own license
as listed below.

If a NOTICE-clause project is added or removed from the runtime classpath
or the bundled webview, this file must be updated in the same commit.

---

## 1. Java / Kotlin runtime dependencies (Gradle)

The following libraries are bundled in the plugin's JAR distribution.

### Apache License 2.0

The following components are licensed under Apache License 2.0
(http://www.apache.org/licenses/LICENSE-2.0). Where an upstream NOTICE file
exists, its contents are reproduced verbatim below.

#### OkHttp (`com.squareup.okhttp3:okhttp` 4.12.0) and Okio (`com.squareup.okio:okio` 3.6.0)
Copyright 2019 Square, Inc.

#### Caffeine (`com.github.ben-manes.caffeine:caffeine` 3.2.3)
Copyright 2014 Ben Manes. Licensed under the Apache License, Version 2.0.

#### Kotlin Standard Library (`org.jetbrains.kotlin:kotlin-stdlib*` 1.9.10)
Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
(Provided by the IntelliJ Platform at runtime; transitively pulled by Okio.)

#### JetBrains Annotations (`org.jetbrains:annotations` 13.0)
Copyright 2000-2023 JetBrains s.r.o.

#### JSpecify (`org.jspecify:jspecify` 1.0.0)
Copyright 2023 The JSpecify Authors.

#### Error Prone Annotations (`com.google.errorprone:error_prone_annotations` 2.43.0)
Copyright 2014 The Error Prone Authors.

#### SQLite JDBC (`org.xerial:sqlite-jdbc` 3.45.3.0)
Copyright Xerial Project. Licensed under the Apache License, Version 2.0.
The SQLite engine itself is in the public domain.

#### IntelliJ Platform (compile-time and runtime extension APIs)
Copyright 2000-2026 JetBrains s.r.o. Used under Apache License, Version 2.0
(IntelliJ Community Platform). The plugin links against the IntelliJ Platform
at runtime; the platform itself is provided by the host IDE installation and
is not redistributed by this plugin.

### MIT License

#### Checker Framework Qualifiers (`org.checkerframework:checker-qual` 3.42.0)
Copyright (c) 2004-2023 The Checker Framework developers.
Licensed under the MIT License.

#### SLF4J API (`org.slf4j:slf4j-api` 1.7.36)
Copyright (c) 2004-2017 QOS.ch. Licensed under the MIT License.
(Excluded from the assembled plugin JAR via root `build.gradle.kts`; included
here for completeness because it appears transitively in the dev classpath.)

### BSD-2-Clause

#### PostgreSQL JDBC Driver (`org.postgresql:postgresql` 42.7.3)
Copyright (c) 1997, PostgreSQL Global Development Group. All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are met:

  1. Redistributions of source code must retain the above copyright notice,
     this list of conditions and the following disclaimer.
  2. Redistributions in binary form must reproduce the above copyright notice,
     this list of conditions and the following disclaimer in the documentation
     and/or other materials provided with the distribution.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
  AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
  IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
  ARE DISCLAIMED.

---

## 2. Bundled JavaScript libraries (`agent/src/main/resources/webview/lib/`)

The following JavaScript libraries are vendored into the plugin JAR and served
to the embedded JCEF browser via `http://workflow-agent/`.

| Library | Version | License | Copyright |
|---------|---------|---------|-----------|
| marked.js | 12.0.2 | MIT | (c) 2018+ MarkedJS contributors; (c) 2011-2018 Christopher Jeffrey |
| DOMPurify | 3.3.3 | (MPL-2.0 OR Apache-2.0) — **elected: Apache-2.0** | (c) Mario Heiderich and DOMPurify contributors |
| Prism.js core + 15 language grammars | 1.30.0 | MIT | (c) 2012 Lea Verou |
| Prism One Dark / One Light themes | (PrismJS themes) | MIT | (c) PrismJS theme contributors |
| ansi_up | 6.0.6 | MIT | (c) 2011-2024 Dru Nelson |
| Chart.js | 4.5.1 | MIT | (c) 2014-2024 Chart.js contributors |
| dagre | 0.8.5 | MIT | (c) 2012-2014 Chris Pettitt |
| diff2html | 3.4.56 | MIT | (c) 2014 Rodrigo Fernandes |
| KaTeX (engine) | 0.16.x | MIT | (c) 2013-2024 Khan Academy and other contributors |
| Mermaid | 11.x | MIT | (c) 2014-2024 Knut Sveidqvist |
| Tailwind Play CDN bundle | (vendored) | MIT | (c) Tailwind Labs Inc. |

**DOMPurify dual-license election:** DOMPurify is offered under either MPL-2.0
or Apache-2.0. This project elects to receive DOMPurify under the **Apache-2.0**
option. No MPL-2.0 file-level copyleft obligations apply.

---

## 2a. Vendored JS / CSS / font provenance (SHA-256)

SHA-256 digests computed with `shasum -a 256` on 2026-04-26.
Paths are relative to the repository root.
Source URLs are the upstream CDN location each file was downloaded from.
Entries marked `(provenance: unknown — verify before next release)` could not be
confirmed to a specific upstream URL with high confidence.

### marked (12.0.2)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/marked.min.js` | marked 12.0.2 | `https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js` | `15fabce5b65898b32b03f5ed25e9f891a729ad4c0d6d877110a7744aa847a894` |

### DOMPurify (3.3.3)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/purify.min.js` | DOMPurify 3.3.3 | `https://cdn.jsdelivr.net/npm/dompurify@3.3.3/dist/purify.min.js` | `a95e028e5efd6a7413d1d18d6d9f918fdad19e2be6e962fcbaa10ab1b364725c` |

### Prism.js core + autoloader (1.30.0)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/prism-core.min.js` | PrismJS 1.30.0 | `https://cdn.jsdelivr.net/npm/prismjs@1.30.0/prism.js` | `002a4435a95bf3490800f714d55169c4555a04896dc8f7b5c7f1886e032c84ef` |
| `agent/src/main/resources/webview/lib/prism-autoloader.min.js` | PrismJS 1.30.0 | `https://cdn.jsdelivr.net/npm/prismjs@1.30.0/plugins/autoloader/prism-autoloader.min.js` | `0233342795c86e2079f7406bce72c481918b9ce416aedeb6b37044abae50fc8d` |

### Prism.js language grammars (1.30.0)

Source URL base: `https://cdn.jsdelivr.net/npm/prismjs@1.30.0/components/`

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/prism-languages/prism-bash.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `6260814110e5182f2956e3bd257429548d9dbf2a9b66a63719b26cf9fac966a7` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-css.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `8c9760dba7f26ea842016919544dd9b73a78a36d5b07a1e9842c333ed18ab6ae` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-diff.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `f16816fb2242a84c6ff6715a48c6d0a3e469e3250912cb9f1b755ca537d02f48` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-gradle.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `55dce1c27583cba7254ee6564d3bbdc2abbda6a9da63b8998dc811d8e2a516cb` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-groovy.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `23797a1e79b83c0216c7ca025671b1d8575305b13ce22b64aa3d2a96b4a3b5ef` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-java.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `4c2dc81dfc9efa51e38a7573938065288c63c64850f01a32f8a7b20a3e24c5a7` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-json.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `956d86baa5ae7ec4106758f354ac2d140bdcd7fc103dece02f73ed12b8d663e4` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-kotlin.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `68c1ddff0d10147c006688289c310ccbfb5283c8687b4bcb9bf7bc9bbdf9f41c` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-markdown.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `9f1166a087d9a9ffb3a833f2bccbe00920b55b41ade02a0b3054b7ab5fbc70ea` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-markup.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `879fc9d256c352d980e053857fa707330853b8bfb67ce284ea661a24dec5756e` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-properties.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `3272abb494806e743c8be4ee9220362c9c06a7282320198ca0bbd365cd8be147` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-python.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `ed4385685bcf2d4935c8dbbab4bde16603da1329e092d2bf36c3dadd67e9a85c` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-sql.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `3fc5f8ce69950ec73adc972f061df42aaea78faa4864709134ea2adc083f3a33` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-typescript.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `852f5513bb9ca9db247f86ecfce74acc91c541749d34929157240518fef8152a` |
| `agent/src/main/resources/webview/lib/prism-languages/prism-yaml.min.js` | PrismJS 1.30.0 | `(provenance: unknown — verify before next release)` | `719c8e8b8c344dc9de510c729f65ba840b1502a0a8e7e25e2ad19ee715f65c02` |

Note: language files have no CDN comment header; the version is inferred from `prism-core.min.js` which explicitly references `prismjs@1.30.0`. Upstream URLs for individual language components follow the pattern `https://cdn.jsdelivr.net/npm/prismjs@1.30.0/components/prism-<lang>.min.js` but cannot be confirmed byte-for-byte without re-downloading.

### Prism themes (prism-themes package)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/prism-themes/prism-one-dark.css` | prism-themes (version: unknown) | `(provenance: unknown — verify before next release)` | `8d58ecf27744a8021fd3d7b414aab04bce9601406712174f2016018c97918f22` |
| `agent/src/main/resources/webview/lib/prism-themes/prism-one-light.css` | prism-themes (version: unknown) | `(provenance: unknown — verify before next release)` | `a61799163e2d48407e0d2724fc7941c5f02e2e1113a399c43c5950d5798e01b8` |

### ansi_up (6.0.6)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/ansi_up.js` | ansi_up 6.0.6 | `(provenance: unknown — verify before next release)` | `554a7d9ca4f3721db1f14941f92dc75b254f57d4b7bffeb84eea1174aa160780` |

### Chart.js (4.5.1)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/chart.min.js` | Chart.js 4.5.1 | `https://cdn.jsdelivr.net/npm/chart.js@4.5.1/dist/chart.umd.min.js` | `48444a82d4edcb5bec0f1965faacdde18d9c17db3063d042abada2f705c9f54a` |

### dagre (0.8.5)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/dagre.min.js` | dagre 0.8.5 | `(provenance: unknown — verify before next release)` | `62eb9787ccfdbdf4148d4d99d31dbf9ee4770eafee81e637d759b52aac22cd51` |

### diff2html (3.4.56)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/diff2html.min.js` | diff2html 3.4.56 | `(provenance: unknown — verify before next release)` | `a2110a09cee157bd5466da77be02107ac81a0baa2bc1f3fe81aac8183314598e` |
| `agent/src/main/resources/webview/lib/diff2html.min.css` | diff2html 3.4.56 | `(provenance: unknown — verify before next release)` | `d3ecc0e9b2b1e5c8466c19de29bed052fd0863475d25829ecc858446efded372` |

### KaTeX (0.16.40)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/katex.min.js` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/katex.min.js` | `759450e87b7d6523ab99fc833330673f400a8cc1a87d0055e1ca261baf98d9e4` |
| `agent/src/main/resources/webview/lib/katex.min.css` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/katex.min.css` | `667d9d323776b39f55e4fb937571dbd9af3cbc0a7f1e81e66786d9908f066993` |

### KaTeX fonts (0.16.40)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/katex-fonts/KaTeX_AMS-Regular.woff2` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/fonts/KaTeX_AMS-Regular.woff2` | `0cdd387c9590a1a9f9794560022dbb59654a7d86f187aa0c81495ad42d3a7308` |
| `agent/src/main/resources/webview/lib/katex-fonts/KaTeX_Main-Bold.woff2` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/fonts/KaTeX_Main-Bold.woff2` | `0f60d1b897938ec918c8ce073092411baf9438f6739465693ff18b0f9d20b021` |
| `agent/src/main/resources/webview/lib/katex-fonts/KaTeX_Main-Italic.woff2` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/fonts/KaTeX_Main-Italic.woff2` | `97479ca6cce906abc961ecac96faa5f9ca2e61b8e7670d475826bcdee9a7c267` |
| `agent/src/main/resources/webview/lib/katex-fonts/KaTeX_Main-Regular.woff2` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/fonts/KaTeX_Main-Regular.woff2` | `c2342cd8b869e01752a9321dc17213fc40d4d04c79688c1d43f2cf316abd7866` |
| `agent/src/main/resources/webview/lib/katex-fonts/KaTeX_Math-Italic.woff2` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/fonts/KaTeX_Math-Italic.woff2` | `7af58c5ec8f132a2ddde9027c6d7814decce4d3b822a11192a42a20e2e973264` |
| `agent/src/main/resources/webview/lib/katex-fonts/KaTeX_Size1-Regular.woff2` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/fonts/KaTeX_Size1-Regular.woff2` | `6b47c40166b6dbe21a5dfca7718413f2147fd2399be1ba605d8ad39cedf25dfe` |
| `agent/src/main/resources/webview/lib/katex-fonts/KaTeX_Size2-Regular.woff2` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/fonts/KaTeX_Size2-Regular.woff2` | `d04c54219f9eaec6d4d4fd42dfb28785975a4794d6b2fc71e566b9cd6db842dd` |
| `agent/src/main/resources/webview/lib/katex-fonts/KaTeX_Size3-Regular.woff2` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/fonts/KaTeX_Size3-Regular.woff2` | `73d591271b1604960cb10bb90fee021670af7297017e0e98480b332d11f51995` |
| `agent/src/main/resources/webview/lib/katex-fonts/KaTeX_Size4-Regular.woff2` | KaTeX 0.16.40 | `https://cdn.jsdelivr.net/npm/katex@0.16.40/dist/fonts/KaTeX_Size4-Regular.woff2` | `a4af7d414440a1c1790825cfb700cf9cf43b0f2c4b04f0ebc523011ad9853ec0` |

### Mermaid (11.13.0)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/src/main/resources/webview/lib/mermaid.min.js` | Mermaid 11.13.0 | `https://cdn.jsdelivr.net/npm/mermaid@11.13.0/dist/mermaid.min.js` | `3e2002bf333907fae7c1d6860bbc78f5da417bc70b64f3d2268581ba0ba8b96a` |

### Tailwind CSS Play CDN (3.4.17)

| File (relative path) | Library + version | Source URL | SHA-256 |
|---|---|---|---|
| `agent/webview/vendor/tailwind-play.js` | Tailwind CSS Play CDN 3.4.17 | `(provenance: unknown — verify before next release)` | `176e894661aa9cdc9a5cba6c720044cbbf7b8bd80d1c9a142a7c24b1b6c50d15` |
| `agent/src/main/resources/webview/dist/vendor/tailwind-play.js` | Tailwind CSS Play CDN 3.4.17 | *(copy of above — byte-identical)* | `176e894661aa9cdc9a5cba6c720044cbbf7b8bd80d1c9a142a7c24b1b6c50d15` |

---

## 3. NPM dependencies (React webview, `agent/webview/`)

The compiled webview bundle (in `agent/src/main/resources/webview/dist/`) is
shipped inside the plugin JAR. The following direct npm dependencies are
incorporated into that bundle.

### MIT License

The following packages are licensed under the MIT License:

react, react-dom, @radix-ui/react-* (accordion, avatar, checkbox, collapsible,
dialog, dropdown-menu, hover-card, popover, progress, scroll-area, select,
separator, slider, slot, switch, tabs, toggle, tooltip), @tanstack/react-table,
@tanstack/react-virtual, @xyflow/react, ansi_up, chart.js, clsx, cmdk, cobe,
colord, dagre, date-fns, diff2html, katex, marked, mermaid, motion,
react-markdown, react-runner, react-simple-maps, recharts, rehype-raw,
remark-breaks, remark-gfm, roughjs, shiki, sucrase, tailwind-merge,
tailwindcss-animate, use-stick-to-bottom, zustand.

Each package retains its respective copyright notice (see the LICENSE file
inside each package's `node_modules/<pkg>/` directory).

### ISC License

#### d3 (7.9.0) and lucide-react (0.577.0)
Copyright (c) the respective project contributors. Licensed under ISC.

### Apache License 2.0

#### class-variance-authority (0.7.1)
Copyright (c) 2022-present Joe Bell.

#### streamdown (2.5.0)
Licensed under the Apache License, Version 2.0.

### MPL-2.0 OR Apache-2.0

#### dompurify (3.3.3)
Dual-licensed; this project elects the Apache-2.0 option (see Section 2 above).

---

## 4. Bundled fonts

### SIL Open Font License 1.1

The following font files are bundled in the plugin JAR and shipped with the
distribution. They are licensed under the SIL Open Font License, Version 1.1
(http://scripts.sil.org/OFL).

#### Fira Code Nerd Font (subset)
- `agent/webview/public/fonts/FiraCodeNerdFont-Regular-subset.woff2`
- `agent/webview/public/fonts/FiraCodeNerdFont-Bold-subset.woff2`

  Copyright (c) 2014, The Fira Code Project Authors
  (https://github.com/tonsky/FiraCode), with Reserved Font Name "Fira Code".

  Nerd Fonts patches Copyright (c) 2014, Ryan L McIntyre
  (https://ryanlmcintyre.com), with Reserved Font Name "Nerd Fonts".

#### KaTeX font subsets
- `KaTeX_AMS-Regular.woff2`, `KaTeX_Caligraphic-*.woff2`,
  `KaTeX_Fraktur-*.woff2`, `KaTeX_Main-*.woff2`, `KaTeX_Math-Italic.woff2`,
  `KaTeX_SansSerif-*.woff2`, `KaTeX_Script-Regular.woff2`,
  `KaTeX_Size{1..4}-Regular.woff2`, `KaTeX_Typewriter-Regular.woff2`

  KaTeX fonts are derived from the AMS-LaTeX, Computer Modern, and STIX font
  families and are distributed under the SIL Open Font License, Version 1.1.

#### SIL Open Font License, Version 1.1 (full text)

  Copyright (c) <date>, <Reserved Font Name>

  This Font Software is licensed under the SIL Open Font License, Version 1.1.
  This license is copied below, and is also available with a FAQ at:
  http://scripts.sil.org/OFL

  -----------------------------------------------------------
  SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
  -----------------------------------------------------------

  PREAMBLE
  The goals of the Open Font License (OFL) are to stimulate worldwide
  development of collaborative font projects, to support the font creation
  efforts of academic and linguistic communities, and to provide a free and
  open framework in which fonts may be shared and improved in partnership
  with others.

  The OFL allows the licensed fonts to be used, studied, modified and
  redistributed freely as long as they are not sold by themselves. The
  fonts, including any derivative works, can be bundled, embedded,
  redistributed and/or sold with any software provided that any reserved
  names are not used by derivative works. The fonts and derivatives,
  however, cannot be released under any other type of license. The
  requirement for fonts to remain under this license does not apply to any
  document created using the fonts or their derivatives.

  DEFINITIONS
  "Font Software" refers to the set of files released by the Copyright
  Holder(s) under this license and clearly marked as such. This may
  include source files, build scripts and documentation.

  "Reserved Font Name" refers to any names specified as such after the
  copyright statement(s).

  "Original Version" refers to the collection of Font Software components as
  distributed by the Copyright Holder(s).

  "Modified Version" refers to any derivative made by adding to, deleting,
  or substituting -- in part or in whole -- any of the components of the
  Original Version, by changing formats or by porting the Font Software to a
  new environment.

  "Author" refers to any designer, engineer, programmer, technical writer
  or other person who contributed to the Font Software.

  PERMISSION & CONDITIONS
  Permission is hereby granted, free of charge, to any person obtaining a
  copy of the Font Software, to use, study, copy, merge, embed, modify,
  redistribute, and sell modified and unmodified copies of the Font
  Software, subject to the following conditions:

  1) Neither the Font Software nor any of its individual components, in
  Original or Modified Versions, may be sold by itself.

  2) Original or Modified Versions of the Font Software may be bundled,
  redistributed and/or sold with any software, provided that each copy
  contains the above copyright notice and this license. These can be
  included either as stand-alone text files, human-readable headers or in
  the appropriate machine-readable metadata fields within text or binary
  files as long as those fields can be easily viewed by the user.

  3) No Modified Version of the Font Software may use the Reserved Font
  Name(s) unless explicit written permission is granted by the
  corresponding Copyright Holder. This restriction only applies to the
  primary font name as presented to the users.

  4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
  Software shall not be used to promote, endorse or advertise any
  Modified Version, except to acknowledge the contribution(s) of the
  Copyright Holder(s) and the Author(s) or with their explicit written
  permission.

  5) The Font Software, modified or unmodified, in part or in whole, must
  be distributed entirely under this license, and must not be distributed
  under any other license. The requirement for fonts to remain under this
  license does not apply to any document created using the Font Software.

  TERMINATION
  This license becomes null and void if any of the above conditions are
  not met.

  DISCLAIMER
  THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
  MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
  OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
  COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
  INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
  DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
  FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
  OTHER DEALINGS IN THE FONT SOFTWARE.

---

## 5. Platform components (NOT bundled, listed for awareness)

The following components are provided by the IntelliJ IDEA host installation
and are NOT redistributed by this plugin. Their licenses apply only to the
IDE itself, not to the plugin distribution.

| Component | License | Notes |
|-----------|---------|-------|
| IntelliJ IDEA Ultimate | JetBrains Commercial Subscription | End-user must license separately. |
| JetBrains Runtime (JBR) | GPL-2.0 with Classpath Exception | Provided by the IDE; Classpath Exception keeps plugin code GPL-clean. |
| JCEF (Java Chromium Embedded Framework) | BSD-3-Clause + LGPL-2.1 (Chromium parts) | Provided by the IDE; not redistributed. |

---

## 6. External services consumed via HTTP (NOT bundled)

The plugin acts as a client to the following services, which the end-user
must already have licensed and deployed. The plugin does not redistribute
any code from these services.

- Atlassian Jira Server / Data Center
- Atlassian Bamboo
- Atlassian Bitbucket Data Center
- SonarQube Server
- Sourcegraph Cody Enterprise
- Sonatype Nexus / Docker Registry v2

---

## Updates

When adding a new dependency to any module's `runtimeClasspath` or to
`agent/webview/package.json`, append the corresponding attribution to this
file in the same commit. Removed dependencies should have their entries
deleted in the commit that removes them.
