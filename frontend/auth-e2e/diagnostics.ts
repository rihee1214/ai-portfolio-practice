import {appendFileSync} from 'node:fs'
import {resolve} from 'node:path'
import type {BrowserContext,Request} from '@playwright/test'
export const diagnosticEnabled=process.env.P09_B_BROWSER_DIAGNOSTICS==='1'
export const diagnosticNow=()=>performance.timeOrigin+performance.now()
export function diagnostic(phase:string,fields:Record<string,unknown>={}){if(diagnosticEnabled)appendFileSync(resolve(process.env.A3_EVIDENCE!,'browser-diagnostics.ndjson'),JSON.stringify({phase,at:diagnosticNow(),pid:process.pid,...fields})+'\n')}
export async function measured<T>(phase:string,run:()=>Promise<T>):Promise<T>{const start=diagnosticNow();diagnostic(phase+':start');try{const result=await run();diagnostic(phase+':end',{elapsed:diagnosticNow()-start});return result}catch(error){diagnostic(phase+':error',{elapsed:diagnosticNow()-start});throw error}}
export function instrument(context:BrowserContext,label:string){
 if(!diagnosticEnabled)return;const starts=new WeakMap<Request,number>(),statuses=new WeakMap<Request,number>();let scripts=0,scriptElapsed=0,documents=0,documentElapsed=0
 const path=(request:Request)=>new URL(request.url()).pathname.replace(/O[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f-]{27,}|\+?\d{9,}/gi,'<id>')
 context.on('request',r=>{starts.set(r,diagnosticNow());if(r.resourceType()==='document'||path(r).startsWith('/api/'))diagnostic('http:start',{label,kind:r.resourceType(),method:r.method(),path:path(r)})})
 context.on('response',r=>{statuses.set(r.request(),r.status())})
 const finish=(r:Request,failed:boolean)=>{const elapsed=diagnosticNow()-(starts.get(r)??diagnosticNow());if(r.resourceType()==='script'){scripts++;scriptElapsed+=elapsed}if(r.resourceType()==='document'){documents++;documentElapsed+=elapsed}if(r.resourceType()==='document'||path(r).startsWith('/api/'))diagnostic('http:end',{label,kind:r.resourceType(),method:r.method(),path:path(r),status:statuses.get(r)??null,failed,elapsed})}
 context.on('requestfinished',r=>finish(r,false));context.on('requestfailed',r=>finish(r,true));context.on('close',()=>diagnostic('context:resources',{label,scripts,scriptElapsed,documents,documentElapsed}))
}
