// CLI used by seal.py. `upload <file>` -> {"rootHash","txHash"} ; `download <rootHash> <outFile>` -> {"ok":true} (with merkle proof check).
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { ZgFile, Indexer } from "@0gfoundation/0g-storage-ts-sdk"
import { ethers } from "ethers"

const here = path.dirname(fileURLToPath(import.meta.url))
const env = path.join(here, "..", ".env")
if (fs.existsSync(env)) for (const l of fs.readFileSync(env, "utf8").split("\n")) {
  const m = l.match(/^\s*([A-Z_]+)\s*=\s*"?([^"]*)"?\s*$/); if (m && !(m[1] in process.env)) process.env[m[1]] = m[2]
}
const RPC = process.env.OG_EVM_RPC ?? "https://evmrpc-testnet.0g.ai"
const IDX = process.env.OG_INDEXER ?? "https://indexer-storage-testnet-turbo.0g.ai"
const indexer = new Indexer(IDX)
const [cmd, a, b] = process.argv.slice(2)
const out = (o) => { console.log(JSON.stringify(o)); process.exit(0) }
const fail = (e) => { console.log(JSON.stringify({ error: String(e?.message ?? e) })); process.exit(1) }

try {
  if (cmd === "upload") {
    const key = process.env.OG_STORAGE_PRIVATE_KEY || process.env.PRIVATE_KEY
    if (!key) fail("PRIVATE_KEY not set")
    const signer = new ethers.Wallet(key, new ethers.JsonRpcProvider(RPC))
    const file = await ZgFile.fromFilePath(a)
    const [tree, terr] = await file.merkleTree(); if (terr !== null) fail(`merkle: ${terr}`)
    const [tx, err] = await indexer.upload(file, RPC, signer); await file.close()
    if (err !== null) fail(`upload: ${err}`)
    out({ rootHash: tx.rootHash ?? tree?.rootHash(), txHash: tx.txHash })
  } else if (cmd === "download") {
    const err = await indexer.download(a, b, true)
    if (err) fail(`download: ${err}`)
    out({ ok: true })
  } else if (cmd === "anchor") {   // anchor <episodeId> <sha256hex> <rootHash> -> EpisodeAnchor.anchor(...) on 0G Chain
    const addr = process.env.ANCHOR_ADDRESS; if (!addr) fail("ANCHOR_ADDRESS not set")
    const key = process.env.OG_STORAGE_PRIVATE_KEY || process.env.PRIVATE_KEY
    const signer = new ethers.Wallet(key, new ethers.JsonRpcProvider(RPC))
    const k = new ethers.Contract(addr, ["function anchor(uint256,bytes32,bytes32)"], signer)
    const [id, sha, root] = [process.argv[3], process.argv[4], process.argv[5]]
    const tx = await k.anchor(BigInt(id), "0x" + sha.replace(/^0x/, ""), root); const r = await tx.wait()
    out({ anchorTx: r.hash })
  } else fail("usage: upload <file> | download <root> <out>")
} catch (e) { fail(e) }
