# Graph Report - wallet  (2026-08-09)

## Corpus Check
- 88 files · ~67,183 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1365 nodes · 4145 edges · 58 communities (37 shown, 21 thin omitted)
- Extraction: 85% EXTRACTED · 15% INFERRED · 0% AMBIGUOUS · INFERRED: 631 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `0d81b616`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- SettingsView
- MiniNumber
- SendView
- JSONArray
- Coin
- .parse
- .buildCard
- BiometricUnlock
- .toString
- Util
- MMRProof
- .build
- MainActivity
- Witness
- Yylex
- .send_buildsSignsAndRoundTrips
- Streamable
- SeedVault
- KeyUses
- JSONObject
- MMR
- Transaction
- MMREntryNumber
- MMRData
- Signature
- Streamable.java
- .aggregate
- .calculateTokenID
- Address
- StateVariable
- MiniData
- MiniFormat
- MiniString
- SignatureProof
- TreeKey
- .ReadFromStream
- Context
- .deriveSignVerifyAndRoundTrip
- .getBytes
- ItemList
- PrefsKeyUses
- .writeDataStream
- .log
- MMREntry
- JSONAware
- BIP39
- JSONStreamAware
- ParseException
- User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly.
- Maths
- Override
- FastByteArrayStream
- SharedPreferences
- gradlew

## God Nodes (most connected - your core abstractions)
1. `MiniData` - 151 edges
2. `MiniNumber` - 117 edges
3. `MainActivity` - 89 edges
4. `JSONObject` - 84 edges
5. `Coin` - 57 edges
6. `MMR` - 54 edges
7. `SettingsView` - 51 edges
8. `MMREntryNumber` - 49 edges
9. `Streamable` - 48 edges
10. `SendView` - 47 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `SeedVault`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/wallet/MainActivity.java → app/src/main/java/com/eurobuddha/wallet/SeedVault.java
- `WalletSession` --references--> `SeedVault`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/wallet/WalletSession.java → app/src/main/java/com/eurobuddha/wallet/SeedVault.java
- `BalancesView` --inherits--> `BaseView`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/wallet/BalancesView.java → app/src/main/java/com/eurobuddha/wallet/BaseView.java
- `MainActivity` --references--> `BalancesView`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/wallet/MainActivity.java → app/src/main/java/com/eurobuddha/wallet/BalancesView.java
- `SendView` --inherits--> `BaseView`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/wallet/SendView.java → app/src/main/java/com/eurobuddha/wallet/BaseView.java

## Import Cycles
- None detected.

## Communities (58 total, 21 thin omitted)

### Community 0 - "SettingsView"
Cohesion: 0.07
Nodes (17): Cb, Context, NodeApi, PairingListener, Context, NodeLink, Button, Context (+9 more)

### Community 1 - "MiniNumber"
Cohesion: 0.09
Nodes (4): MathContext, MiniNumber, Token, GeneralParams

### Community 2 - "SendView"
Cohesion: 0.06
Nodes (20): Design, Context, Mode, CURRENT, ORIGINAL_DARK, ORIGINAL_LIGHT, Button, Context (+12 more)

### Community 3 - "JSONArray"
Cohesion: 0.17
Nodes (5): CoinSelector, InsufficientFundsException, JSONArray, CoinSelectorTest, Test

### Community 4 - "Coin"
Cohesion: 0.12
Nodes (6): Coin, DataInputStream, DataOutputStream, Override, CoinJsonTest, Test

### Community 5 - ".parse"
Cohesion: 0.11
Nodes (5): JSONValue, ContainerFactory, ContentHandler, JSONParser, Yytoken

### Community 6 - ".buildCard"
Cohesion: 0.05
Nodes (22): BalancesView, Bitmap, LinearLayout, Override, TextView, View, IconResolver, Identicon (+14 more)

### Community 7 - "BiometricUnlock"
Cohesion: 0.12
Nodes (10): BiometricUnlock, Callback, Context, SharedPreferences, UnlockCallback, AuthenticationCallback, Cipher, FragmentActivity (+2 more)

### Community 8 - ".toString"
Cohesion: 0.19
Nodes (3): Override, Override, JSONWriter

### Community 9 - "Util"
Cohesion: 0.20
Nodes (3): Util, Test, UtilAddressTest

### Community 10 - "MMRProof"
Cohesion: 0.17
Nodes (5): DataInputStream, DataOutputStream, Override, MMRProof, MMRProofChunk

### Community 11 - ".build"
Cohesion: 0.15
Nodes (3): InputCoin, Output, TxnFactory

### Community 12 - "MainActivity"
Cohesion: 0.06
Nodes (28): ActivityResultLauncher, BaseView, SuppressWarnings, View, Button, EditText, LinearLayout, OnClickListener (+20 more)

### Community 13 - "Witness"
Cohesion: 0.07
Nodes (12): DataInputStream, DataOutputStream, Override, TxnRow, CoinProof, DataInputStream, DataOutputStream, Override (+4 more)

### Community 15 - ".send_buildsSignsAndRoundTrips"
Cohesion: 0.19
Nodes (4): BuiltTxn, InMemoryKeyUses, Test, TxnFactoryTest

### Community 17 - "SeedVault"
Cohesion: 0.05
Nodes (20): BlobStore, PrefsBlobStore, SeedVault, SigningNotAllowedException, VaultCorruptException, SessionPolicy, InMemoryKeyUses, Test (+12 more)

### Community 18 - "KeyUses"
Cohesion: 0.07
Nodes (11): KeyUses, SuppressWarnings, VaultBlob, BadPassphraseException, VaultCrypto, InMemoryKeyUses, Test, VaultSecurityTest (+3 more)

### Community 22 - "Transaction"
Cohesion: 0.08
Nodes (8): DataInputStream, DataOutputStream, Override, DataInputStream, DataOutputStream, Override, Transaction, TxPoWGenerator

### Community 23 - "MMREntryNumber"
Cohesion: 0.16
Nodes (4): DataOutputStream, MathContext, Override, MMREntryNumber

### Community 24 - "MMRData"
Cohesion: 0.21
Nodes (4): DataInputStream, DataOutputStream, Override, MMRData

### Community 25 - "Signature"
Cohesion: 0.21
Nodes (4): DataInputStream, DataOutputStream, Override, Signature

### Community 28 - ".calculateTokenID"
Cohesion: 0.39
Nodes (3): DataInputStream, DataOutputStream, Override

### Community 29 - "Address"
Cohesion: 0.12
Nodes (8): Address, DataInputStream, DataOutputStream, Override, DataInputStream, DataOutputStream, Override, ScriptProof

### Community 30 - "StateVariable"
Cohesion: 0.13
Nodes (7): DataInputStream, DataOutputStream, Override, MiniByte, DataInputStream, Override, StateVariable

### Community 31 - "MiniData"
Cohesion: 0.19
Nodes (3): DataInputStream, DataOutputStream, MiniData

### Community 33 - "MiniString"
Cohesion: 0.18
Nodes (6): DataInputStream, DataOutputStream, Override, MiniString, DataOutputStream, Charset

### Community 34 - "SignatureProof"
Cohesion: 0.17
Nodes (4): SignatureProof, TreeKeyNode, Winternitz, WinternitzOTSignature

### Community 36 - ".ReadFromStream"
Cohesion: 0.29
Nodes (4): DataInputStream, DataOutputStream, Override, DataInputStream

### Community 41 - "PrefsKeyUses"
Cohesion: 0.36
Nodes (4): Context, Override, SharedPreferences, PrefsKeyUses

### Community 42 - ".writeDataStream"
Cohesion: 0.29
Nodes (3): DataInputStream, DataOutputStream, Override

### Community 43 - ".log"
Cohesion: 0.32
Nodes (3): Crypto, MinimaLogger, SimpleDateFormat

### Community 44 - "MMREntry"
Cohesion: 0.26
Nodes (4): DataInputStream, DataOutputStream, Override, MMREntry

### Community 55 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **7 isolated node(s):** `RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.`, `ORIGINAL_LIGHT`, `ORIGINAL_DARK`, `CURRENT`, `SEND` (+2 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **21 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `SettingsView`, `SendView`, `JSONArray`, `.buildCard`, `BiometricUnlock`, `.toString`, `.deriveSignVerifyAndRoundTrip`, `PrefsKeyUses`, `.build`, `SeedVault`, `KeyUses`?**
  _High betweenness centrality (0.307) - this node is a cross-community bridge._
- **Why does `MiniData` connect `MiniData` to `MiniNumber`, `SendView`, `Coin`, `MMRProof`, `.build`, `Witness`, `.send_buildsSignsAndRoundTrips`, `Streamable`, `JSONObject`, `Transaction`, `MMRData`, `Signature`, `.calculateTokenID`, `Address`, `StateVariable`, `MiniString`, `SignatureProof`, `TreeKey`, `.deriveSignVerifyAndRoundTrip`, `.getBytes`, `.writeDataStream`, `.log`, `BIP39`, `MiniFormat.java`, `Maths`?**
  _High betweenness centrality (0.180) - this node is a cross-community bridge._
- **Why does `JSONObject` connect `JSONObject` to `SettingsView`, `SendView`, `JSONArray`, `Coin`, `.buildCard`, `.toString`, `Util`, `.build`, `MainActivity`, `Witness`, `Streamable`, `Transaction`, `.aggregate`, `StateVariable`, `MiniFormat`, `MiniString`, `TreeKey`, `MMREntry`, `JSONAware`, `JSONStreamAware`, `MiniFormat.java`?**
  _High betweenness centrality (0.162) - this node is a cross-community bridge._
- **What connects `RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.`, `ORIGINAL_LIGHT`, `ORIGINAL_DARK` to the rest of the system?**
  _7 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `SettingsView` be split into smaller, more focused modules?**
  _Cohesion score 0.06544566544566545 - nodes in this community are weakly interconnected._
- **Should `MiniNumber` be split into smaller, more focused modules?**
  _Cohesion score 0.09080841638981174 - nodes in this community are weakly interconnected._
- **Should `SendView` be split into smaller, more focused modules?**
  _Cohesion score 0.063003663003663 - nodes in this community are weakly interconnected._