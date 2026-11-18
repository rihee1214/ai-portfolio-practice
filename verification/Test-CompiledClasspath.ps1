$ErrorActionPreference='Stop'
. "$PSScriptRoot/CompiledClasspath.ps1"
$root=Split-Path $PSScriptRoot -Parent
$folder=Join-Path $root ('.local-tools/compiled-classpath-'+[guid]::NewGuid().ToString('N'))
$classes=Join-Path $folder 'common/classes';$tests=Join-Path $folder 'common/test-classes';$build=Join-Path $folder 'private-build';$cwd=Join-Path $folder 'backend'
New-Item -ItemType Directory -Path $classes,$tests,$build,$cwd|Out-Null
$number=0
function New-ClasspathReport([string]$Value,[bool]$IncludeWorkingDirectory=$true){
    $script:number++;$reportFolder=Join-Path $folder "report-$script:number";New-Item -ItemType Directory -Path $reportFolder|Out-Null
    $valueXml=[Security.SecurityElement]::Escape($Value);$cwdXml=[Security.SecurityElement]::Escape($cwd)
    $working=if($IncludeWorkingDirectory){"<property name=`"user.dir`" value=`"$cwdXml`"/>"}else{''}
    "<testsuite><properties><property name=`"surefire.test.class.path`" value=`"$valueXml`"/>$working</properties></testsuite>"|Set-Content -Encoding utf8 "$reportFolder/TEST-fixture.xml"
    $reportFolder
}
function Read-Fixture([string]$Report){Read-VerifiedCompiledClasspath -ReportDirectory $Report -ClassesDirectory $classes -TestClassesDirectory $tests -PrivateBuild $build}
function Reject-Fixture([string]$Report){$rejected=$false;try{Read-Fixture $Report|Out-Null}catch{$rejected=$true};if(-not $rejected){throw 'Accepted invalid compiled classpath'}}
$proof=Read-Fixture (New-ClasspathReport "$classes;$tests;")
if($proof.emptyComponentCount -ne 1 -or $proof.javaWorkingDirectory -ine $cwd -or -not $proof.privateCompiledCopiesAbsent){throw 'Trailing empty Java classpath component misinterpreted'}
$internal=Read-Fixture (New-ClasspathReport "$classes;;$tests;")
if($internal.emptyComponentCount -ne 2){throw 'Internal empty classpath component lost'}
Reject-Fixture (New-ClasspathReport "$tests;")
Reject-Fixture (New-ClasspathReport ';')
Reject-Fixture (New-ClasspathReport "$classes;$tests;" $false)
New-Item -ItemType Directory -Path "$build/classes"|Out-Null
Reject-Fixture (New-ClasspathReport "$classes;$tests;")
@{status='passed';assertions=6;emptyComponentMeaning='Java working directory';commonDirectoriesStillRequired=$true;privateCopiesStillRejected=$true;evidence=$folder}|ConvertTo-Json -Compress
