import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import ts from 'typescript'

test('every product browser scope has a finite declared inventory and uses the guarded fixture',()=>{
  const dir=fileURLToPath(new URL('../auth-e2e/',import.meta.url))
  const {entries}=JSON.parse(readFileSync(join(dir,'deadline-inventory.json'),'utf8'))
  assert.ok(entries.length>0)
  const found=new Set()
  for(const file of readdirSync(dir).filter(f=>f.endsWith('.ts')&&!['business-deadline.ts','deadline-core.ts','deadline-reporter.ts','deadline-evidence.ts','playwright.config.ts'].includes(f))) {
    const source=ts.createSourceFile(file,readFileSync(join(dir,file),'utf8'),ts.ScriptTarget.Latest,true)
    function visit(node) {
      if(ts.isImportDeclaration(node)&&file.endsWith('.spec.ts'))assert.notEqual(node.moduleSpecifier.text,'@playwright/test','unguarded test fixture: '+file)
      if(ts.isCallExpression(node)) {
        assert.notEqual(node.expression.getText(source),'test.setTimeout','scenario total timeout returned: '+file)
        if(ts.isIdentifier(node.expression)&&['operation','prepare','cleanup'].includes(node.expression.text)) {
          const name=node.arguments[0]
          assert.ok(ts.isStringLiteralLike(name),'scope names must be statically reviewable: '+file)
          const entry=entries.find(e=>e.file===file&&e.kind===node.expression.text&&e.name===name.text)
          assert.ok(entry,'undeclared command boundary: '+file+':'+name.text)
          assert.ok(Number.isInteger(entry.maxOccurrencesPerTest)&&entry.maxOccurrencesPerTest>0)
          found.add(entry)
        }
      }
      ts.forEachChild(node,visit)
    }
    visit(source)
  }
  assert.equal(found.size,entries.filter(e=>e.file!=='business-deadline.ts').length,'stale or duplicate scope declaration')
  const config=readFileSync(join(dir,'playwright.config.ts'),'utf8')
  assert.match(config,/timeout: 0/)
  assert.match(config,/A3_DEADLINE_INVENTORY = resolve/)
  assert.match(config,/workers: 1, retries: 0/)
})
