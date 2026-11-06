// node deploy.mjs -> compiles contracts/EpisodeAnchor.sol with solc-js and deploys to 0G Galileo; prints the address. Put it in .env as ANCHOR_ADDRESS.
import fs from "node:fs"; import path from "node:path"; import { fileURLToPath } from "node:url"
import solc from "solc"; import { ethers } from "ethers"
const here = path.dirname(fileURLToPath(import.meta.url))
const env = path.join(here, "..", ".env")
if (fs.existsSync(env)) for (const l of fs.readFileSync(env, "utf8").split("\n")) { const m = l.match(/^\s*([A-Z_]+)\s*=\s*"?([^"#]*)"?/); if (m && !(m[1] in process.env)) process.env[m[1]] = m[2].trim() }
const src = fs.readFileSync(path.join(here, "contracts/EpisodeAnchor.sol"), "utf8")
const out = JSON.parse(solc.compile(JSON.stringify({ language: "Solidity", sources: { "EpisodeAnchor.sol": { content: src } },
  settings: { optimizer: { enabled: true, runs: 200 }, outputSelection: { "*": { "*": ["abi", "evm.bytecode.object"] } } } })))
if (out.errors?.some((e) => e.severity === "error")) { console.error(out.errors.map((e) => e.formattedMessage).join("\n")); process.exit(1) }
const c = out.contracts["EpisodeAnchor.sol"].EpisodeAnchor
fs.writeFileSync(path.join(here, "contracts/EpisodeAnchor.abi.json"), JSON.stringify(c.abi, null, 1))
const w = new ethers.Wallet(process.env.PRIVATE_KEY, new ethers.JsonRpcProvider(process.env.OG_EVM_RPC ?? "https://evmrpc-testnet.0g.ai"))
const f = new ethers.ContractFactory(c.abi, "0x" + c.evm.bytecode.object, w)
const k = await f.deploy(); const r = await k.deploymentTransaction().wait()
console.log(JSON.stringify({ address: await k.getAddress(), tx: r.hash, deployer: w.address, explorer: "https://chainscan-galileo.0g.ai/address/" + await k.getAddress() }))
