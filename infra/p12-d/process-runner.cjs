'use strict';
// Adapted from verification/package-execution.mjs: owned process tree termination
// precedes rejection; output drain is separately bounded even if close never arrives.
const fs=require('node:fs');
const {spawn,execFile}=require('node:child_process');
function alive(pid){try{process.kill(pid,0);return true;}catch{return false;}}
async function terminateTree(child,timeout=2000){
  if(!child?.pid || !alive(child.pid))return;
  if(child.pid===process.pid || child.pid===process.ppid)throw new Error('Supervisor termination refused');
  if(process.platform==='win32')await new Promise((resolve,reject)=>execFile('taskkill',['/PID',String(child.pid),'/T','/F'],{timeout,windowsHide:true},error=>error&&alive(child.pid)?reject(new Error('Owned process tree termination failed')):resolve()));
  else{try{process.kill(-child.pid,'SIGKILL');}catch{if(alive(child.pid))child.kill('SIGKILL');}}
}
function runOwned(file,args,{cwd,env,input,output,outputDescriptor,timeout=60000,maxBuffer=16*1024*1024,onSpawn}={}){
  return new Promise((resolve,reject)=>{
    let child,fd=null,timer,drain,done=false,stopping=false,stdout=[],stderr=[],bytes=0;
    function finish(error,code,timedOut=false){
      if(done)return;done=true;clearTimeout(timer);clearTimeout(drain);
      if(fd!==null){fs.closeSync(fd);fd=null;}
      if(input?.destroy)input.destroy();
      child?.stdin.destroy();child?.stdout.destroy();child?.stderr.destroy();child?.unref();
      const result={out:Buffer.concat(stdout).toString('utf8'),err:Buffer.concat(stderr).toString('utf8'),code,timedOut};
      if(error){error.result=result;reject(error);}else resolve(result);
    }
    async function stop(reason,timedOut){
      if(stopping||done)return;stopping=true;
      // This timer is independent of stdio close and also bounds tree cleanup.
      drain=setTimeout(()=>finish(new Error(reason+'; finite cleanup/drain expired'),child.exitCode,timedOut),3000);
      try{await terminateTree(child);}
      catch(e){return finish(new Error(reason+'; '+e.message),child.exitCode,timedOut);}
      if(child.exitCode!==null || child.signalCode!==null)finish(new Error(reason),child.exitCode,timedOut);
    }
    try{
      if(outputDescriptor!==undefined){if(output || !Number.isInteger(outputDescriptor) || outputDescriptor<0)throw new Error('Invalid owned output descriptor');fd=outputDescriptor;}
      else if(output)fd=fs.openSync(output,'wx');
      child=spawn(file,args,{cwd,env,windowsHide:true,detached:process.platform!=='win32',stdio:['pipe','pipe','pipe']});
      onSpawn?.(child.pid);
      timer=setTimeout(()=>stop('Owned command phase deadline exceeded',true),timeout);
      child.stdout.on('data',b=>{if(done)return;try{if(fd!==null)fs.writeSync(fd,b);else{bytes+=b.length;if(bytes>maxBuffer)stop('Owned command output limit exceeded',false);else stdout.push(b);}}catch{stop('Owned command output persistence failed',false);}});
      child.stderr.on('data',b=>{if(done)return;if(Buffer.concat(stderr).length<1024*1024)stderr.push(b);});
      child.on('error',e=>finish(e,null));
      child.on('close',code=>{if(!stopping)finish(null,code);});
      child.stdin.on('error',()=>{});
      if(input && typeof input!=='string' && !Buffer.isBuffer(input)){input.on('error',()=>stop('Owned input stream failed',false));input.pipe(child.stdin);}
      else child.stdin.end(input);
    }catch(e){finish(e,null);}
  });
}
module.exports={runOwned,terminateTree,alive};
