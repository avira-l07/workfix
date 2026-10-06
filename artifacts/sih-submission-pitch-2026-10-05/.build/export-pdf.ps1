$ErrorActionPreference = 'Stop'
$sourceDeck = 'D:\new itantra\artifacts\sih-submission-pitch-2026-10-05\output\iTantra_SIH26173_submission.pptx'
$targetPdf = 'D:\new itantra\artifacts\sih-submission-pitch-2026-10-05\output\pdf\iTantra_SIH26173_submission.pdf'
$existingPowerPoint = @(Get-Process -Name POWERPNT -ErrorAction SilentlyContinue).Count -gt 0
$presentationApp = $null
$deck = $null
try {
    $presentationApp = New-Object -ComObject PowerPoint.Application
    $presentationApp.AutomationSecurity = 3
    $deck = $presentationApp.Presentations.Open($sourceDeck, -1, 0, 0)
    if ($deck.Slides.Count -ne 6) { throw 'Expected exactly six slides' }
    $deck.SaveAs($targetPdf, 32)
    Get-Item -LiteralPath $targetPdf | Select-Object FullName, Length
} finally {
    if ($null -ne $deck) {
        $deck.Close()
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($deck)
    }
    if ($null -ne $presentationApp) {
        if (-not $existingPowerPoint) { $presentationApp.Quit() }
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($presentationApp)
    }
}
