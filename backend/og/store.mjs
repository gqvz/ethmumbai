// Uploads each finished 30 s archive WAV (../data/archive) to 0G Storage; logs root hashes to ../data/og_storage.jsonl
//. Needs PRIVATE_KEY (funded with 0G testnet tokens: faucet.0g.ai).
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
const key = process.env.OG_STORAGE_PRIVATE_KEY || process.env.PRIVATE_KEY
if (!key) { console.error("PRIVATE_KEY / OG_STORAGE_PRIVATE_KEY not set (backend/.env)"); process.exit(1) }
const RPC = process.env.OG_EVM_RPC ?? "https://evmrpc-testnet.0g.ai"
const IDX = process.env.OG_INDEXER ?? "https://indexer-storage-testnet-turbo.0g.ai"
const signer = new ethers.Wallet(key, new ethers.JsonRpcProvider(RPC))
const indexer = new Indexer(IDX)

const dir = path.join(here, "..", "data", "archive")
const log = path.join(here, "..", "data", "og_storage.jsonl")
const done = new Set(fs.existsSync(log) ? fs.readFileSync(log, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l).file) : [])

async function pump() {
  if (!fs.existsSync(dir)) return
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith(".wav") && !done.has(x)).sort()) {
    const p = path.join(dir, f)
    if (Date.now() - fs.statSync(p).mtimeMs < 2000) continue   // still being written
    try {
      const file = await ZgFile.fromFilePath(p)
      const [tree, terr] = await file.merkleTree(); if (terr !== null) throw new Error(`merkle: ${terr}`)
      const [tx, err] = await indexer.upload(file, RPC, signer); await file.close()
      if (err !== null) throw new Error(`upload: ${err}`)
      const rec = { file: f, rootHash: tx.rootHash ?? tree?.rootHash(), txHash: tx.txHash }
      fs.appendFileSync(log, JSON.stringify(rec) + "\n")
      fs.appendFileSync(outbox, JSON.stringify({ ts: new Date().toISOString().slice(0, 19), type: "archive", label: "0g-storage", ...rec }) + "\n")
      done.add(f); console.log("uploaded", rec)
    } catch (e) { console.error("upload failed, will retry:", e.message ?? e) }
  }
}
setInterval(pump, 5000); pump()
