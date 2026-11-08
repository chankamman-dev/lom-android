// 在 RuntimeInitializeOnLoads.json 里登记一个启动方法（Unity 在第一个场景载入后调用它）。
// usage: node add-runtime-init.js <json> <程序集> <命名空间> <类> <方法>
const fs = require('fs');
const [file, assemblyName, nameSpace, className, methodName] = process.argv.slice(2);
const j = JSON.parse(fs.readFileSync(file, 'utf8'));
if (!j.root.some(e => e.assemblyName === assemblyName && e.className === className && e.methodName === methodName)) {
  j.root.push({ assemblyName, nameSpace, className, methodName, loadTypes: 0, isUnityClass: false });
}
fs.writeFileSync(file, JSON.stringify(j));
console.log(`${file}: ${j.root.length} runtime init methods`);
