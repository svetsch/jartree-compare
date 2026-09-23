# Prints the project version without the -SNAPSHOT suffix, as jpackage only accepts numeric versions.
$pom = [xml](Get-Content -Raw (Join-Path $PSScriptRoot '..\pom.xml'))
($pom.project.version -replace '-SNAPSHOT', '')
