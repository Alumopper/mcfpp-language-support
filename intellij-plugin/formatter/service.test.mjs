import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import readline from 'node:readline';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const service=fileURLToPath(new URL('./service.mjs',import.meta.url));
const linter=fileURLToPath(new URL('../../../MCFPP-Linter/',import.meta.url));
async function fixture(action) {
  const root=await mkdtemp(path.join(tmpdir(),'mcfpp-formatter-'));
  const child=spawn(process.execPath,[service],{env:{...process.env,MCFPP_LINTER_HOME:linter},stdio:['pipe','pipe','pipe']});
  const pending=new Map(); let sequence=0; let errors='';
  const lines=readline.createInterface({input:child.stdout});
  lines.on('line',line=>{const value=JSON.parse(line);pending.get(value.id)?.resolve(value);pending.delete(value.id);});
  child.stderr.on('data',chunk=>errors+=chunk);
  child.on('close',code=>{for(const callback of pending.values())callback.reject(new Error('Service stopped: '+code+' '+errors));pending.clear();});
  const request=(operation,source,options={})=>new Promise((resolve,reject)=>{
    const id=++sequence; pending.set(id,{resolve,reject});
    child.stdin.write(JSON.stringify({id,operation,filePath:path.join(root,'main.mcfpp'),source,fix:true,...options})+'\n');
  });
  try {await action({root,request});}
  finally { child.stdin.end(); child.kill(); lines.close(); await rm(root,{recursive:true,force:true}); }
}

test('save pipeline formats safely and is idempotent',()=>fixture(async({request})=>{
  const original='func answer(){\nvar value=1   \nreturn value\n}';
  const result=await request('format',original,{cursorOffset:original.indexOf('value=')});
  assert.equal(result.ok,true,JSON.stringify(result));
  assert.equal(result.source,'func answer() {\n    var value = 1\n    return value\n}\n');
  assert.equal((await request('format',result.source)).source,result.source);
  assert.equal(result.diagnostics.length,0);
  assert.equal(result.source.slice(result.cursorOffset,result.cursorOffset+5),'value');
  const second='func run() {   \nvar value=1\n}';
  const mapped=await request('format',second,{cursorOffset:second.indexOf('value=')});
  assert.equal(mapped.source.slice(mapped.cursorOffset,mapped.cursorOffset+5),'value');
}));

test('project configuration and ignore rules are refreshed',()=>fixture(async({root,request})=>{
  await writeFile(path.join(root,'.prettierrc.json'),JSON.stringify({tabWidth:2}));
  let result=await request('format','func run(){\nprint(1)\n}');
  assert.equal(result.ok,true,JSON.stringify(result));
  assert.ok(result.source.includes('\n  print(1)'));
  await writeFile(path.join(root,'.prettierignore'),'main.mcfpp\n');
  result=await request('format','func run(){   \nprint(1)\n}');
  assert.equal(result.ignored,true);
  assert.equal(result.source,'func run(){   \nprint(1)\n}');
}));

test('lint fixes use UTF-16 offsets and preserve protected text',()=>fixture(async({request})=>{
  const source='var text = "😀  ";  \n';
  const lint=await request('lint',source);
  assert.equal(lint.ok,true);
  assert.deepEqual(lint.diagnostics,[]);
  assert.equal(lint.source,source);
  const fixed=await request('fix',source);
  assert.equal(fixed.source,'var text = "😀  ";\n');
  const protectedSource='func run() {\n/say hello  \n}\n';
  const result=await request('format',protectedSource);
  assert.equal(result.ok,true,JSON.stringify(result));
  assert.ok(result.source.includes('/say hello  \n'));
}));

test('editor accepts whitespace while formatting cleans it and syntax errors stay visible',()=>fixture(async({request})=>{
  const original='func main() {  \n\t print(1)  \n}';
  assert.deepEqual((await request('lint',original)).diagnostics,[]);
  const formatted=await request('format',original);
  assert.equal(formatted.source,'func main() {\n    print(1)\n}\n');
  assert.ok((await request('lint','func main() {')).diagnostics.some(d=>d.severity==='error'));
}));

test('malformed source reports an error without supplying a replacement',()=>fixture(async({request})=>{
  const result=await request('format','var text = "unfinished');
  assert.equal(result.ok,false);
  assert.ok(result.error.includes('unterminated'));
  assert.equal(result.source,undefined);
}));

