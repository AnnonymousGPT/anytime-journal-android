param(
    [Parameter(Mandatory = $true)]
    [string] $ProjectUrl,

    [Parameter(Mandatory = $true)]
    [string] $AnonKey,

    [string[]] $Devices = @(
        "192.168.137.181:40317",
        "192.168.137.188:34555"
    )
)

$adb = "C:\Users\daksh\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$package = "com.daksh.anytimejournal/.MainActivity"

foreach ($device in $Devices) {
    & $adb -s $device shell am start `
        -n $package `
        --ez open_collab true `
        --es cloud_url "$ProjectUrl" `
        --es cloud_anon_key "$AnonKey"
}
