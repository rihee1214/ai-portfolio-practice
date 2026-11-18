function Read-VerifiedCompiledClasspath {
    param([string]$ReportDirectory,[string]$ClassesDirectory,[string]$TestClassesDirectory,[string]$PrivateBuild)
    $report=Get-ChildItem -LiteralPath $ReportDirectory -Filter 'TEST-*.xml'|Select-Object -First 1
    if(-not $report){throw 'Actual Failsafe classpath report missing'}
    [xml]$xml=Get-Content -Encoding utf8 -LiteralPath $report.FullName -Raw
    $property=@($xml.testsuite.properties.property|Where-Object name -CEQ 'surefire.test.class.path')
    if($property.Count -ne 1){throw 'Actual Failsafe classpath evidence missing or duplicate'}
    $parts=@(([string]$property[0].value).Split([IO.Path]::PathSeparator))
    # Java's empty classpath component means user.dir. It is not an explicit common-directory entry.
    $empty=@($parts|Where-Object{$_ -ceq ''}).Count
    $explicit=@($parts|Where-Object{$_ -cne ''}|ForEach-Object{[IO.Path]::GetFullPath($_)})
    $workingDirectory=$null
    if($empty){
        $cwd=@($xml.testsuite.properties.property|Where-Object name -CEQ 'user.dir')
        if($cwd.Count -ne 1 -or -not [IO.Path]::IsPathRooted([string]$cwd[0].value)){throw 'Java empty classpath component requires actual working directory evidence'}
        $workingDirectory=[IO.Path]::GetFullPath([string]$cwd[0].value)
    }
    foreach($directory in @($ClassesDirectory,$TestClassesDirectory)){
        $required=[IO.Path]::GetFullPath($directory)
        if(-not @($explicit|Where-Object{[string]::Equals($_,$required,[StringComparison]::OrdinalIgnoreCase)}).Count){throw 'Actual Failsafe did not explicitly consume shared compiled classes'}
    }
    if((Test-Path -LiteralPath (Join-Path $PrivateBuild 'classes')) -or (Test-Path -LiteralPath (Join-Path $PrivateBuild 'test-classes'))){throw 'Unexpected copied private compiled classes'}
    @{classesDirectory=$ClassesDirectory;testClassesDirectory=$TestClassesDirectory;privateCompiledCopiesAbsent=$true;emptyComponentCount=$empty;emptyComponentMeaning='Java working directory';javaWorkingDirectory=$workingDirectory}
}
