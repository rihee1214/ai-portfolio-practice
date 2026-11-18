'use strict';
// The owned runners retain UTF-8, argument arrays, finite phases and tree cleanup.
const path=require('node:path');
const executable='C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe';
const quote=value=>"'"+String(value).replaceAll("'","''")+"'";
function assertExecutable(value){
  if(typeof value!=='string'||path.resolve(value).toLowerCase()!==path.resolve(executable).toLowerCase())throw Error('Exact explicit PowerShell 7.6.9 executable required; no fallback');
  return executable;
}
function commandArguments(script,{helper=path.join(__dirname,'PowerShellRuntime.ps1')}={}){
  if(typeof script!=='string'||!script.length||!path.isAbsolute(helper))throw Error('Explicit PowerShell command/helper required');
  return ['-NoLogo','-NoProfile','-NonInteractive','-Command',`. ${quote(helper)}; $null=Assert-VerificationPowerShellRuntime; ${script}`];
}
function checkedArguments(args){
  const at=args.findIndex(value=>['-Command','-File'].includes(value));
  if(at<0)throw Error('PowerShell child must declare a command or script');
  if(args[at]==='-Command')return commandArguments(args[at+1]);
  return commandArguments('& '+quote(args[at+1])+' '+args.slice(at+2).map(value=>/^-[A-Za-z][A-Za-z0-9]*$/.test(value)?value:quote(value)).join(' ')+'; if (-not $?) { exit 1 }');
}
module.exports={executable,assertExecutable,commandArguments,checkedArguments};
