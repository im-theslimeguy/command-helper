import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const projectRoot = path.resolve(__dirname, "..");
const outputDir = path.join(projectRoot, ".pages");
const wikiDir = path.join(projectRoot, "wiki");

async function copyRootSite() {
  await mkdir(outputDir, { recursive: true });

  const indexSource = await readFile(path.join(projectRoot, "index.html"), "utf8");
  const pagesIndex = indexSource.replace('href="./docs/"', 'href="./wiki/"');

  await writeFile(path.join(outputDir, "index.html"), pagesIndex, "utf8");
  await cp(path.join(projectRoot, "styles.css"), path.join(outputDir, "styles.css"));
  await cp(path.join(projectRoot, "main.js"), path.join(outputDir, "main.js"));
  await cp(path.join(projectRoot, "assets"), path.join(outputDir, "assets"), { recursive: true });
  await cp(path.join(projectRoot, "components"), path.join(outputDir, "components"), { recursive: true });
  await writeFile(path.join(outputDir, ".nojekyll"), "");
}

function runDocsBuild() {
  return new Promise((resolve, reject) => {
    const child = spawn("npm run docs:build", {
      cwd: projectRoot,
      stdio: "inherit",
      shell: true,
      env: {
        ...process.env,
        BUILD_TARGET: "pages"
      }
    });

    child.on("exit", (code) => {
      if (code === 0) {
        resolve();
        return;
      }

      reject(new Error(`docs build failed with exit code ${code}`));
    });
    child.on("error", reject);
  });
}

await rm(outputDir, { recursive: true, force: true });
await copyRootSite();
await runDocsBuild();
await rm(wikiDir, { recursive: true, force: true });
await cp(path.join(outputDir, "wiki"), wikiDir, { recursive: true });
