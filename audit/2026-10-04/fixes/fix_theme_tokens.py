"""Replace known mockup color literals with matching native semantic tokens."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
colors = {
    'F1F5F9': 'SurfaceVariant', 'F8FAFC': 'CanvasBg', 'E2E8F0': 'BorderSubtle', 'CBD5E1': 'BorderStrong',
    'EFF6FF': 'AccentSubtle', 'DBEAFE': 'AccentSubtle', 'E3F2FD': 'AccentSubtle',
    'FEE2E2': 'ErrorContainer', 'FFE2E2': 'ErrorContainer', 'FFEBEE': 'ErrorContainer',
    'FECACA': 'ErrorContainer', 'FCA5A5': 'StatusDanger', 'EF9A9A': 'StatusDanger',
    '991B1B': 'OnErrorContainer', 'B91C1C': 'OnErrorContainer', 'DC2626': 'StatusDanger',
    'ECFDF5': 'SuccessContainer', 'D1FAE5': 'SuccessContainer', 'E8F5E9': 'SuccessContainer',
    '81C784': 'StatusSuccess', '2E7D32': 'OnSuccessContainer', '047857': 'OnSuccessContainer',
    'FEF3C7': 'WarningContainer', 'FEF9C3': 'WarningContainer', 'FFFFFBEB': 'WarningContainer',
    'FFFBEB': 'WarningContainer', 'FFF8E1': 'WarningContainer', 'FDE68A': 'WarningContainer',
    'FFD54F': 'StatusWarning', 'E65100': 'OnWarningContainer', 'B45309': 'OnWarningContainer',
    'F5F5F5': 'SurfaceVariant', 'E0E0E0': 'BorderSubtle',
}
for p in (ROOT / 'app/src/main/java/com/example/itantra').rglob('*.kt'):
    if 'theme' in p.parts:
        continue
    old = p.read_text(encoding='utf-8')
    text = old
    for literal, token in colors.items():
        text = text.replace(f'Color(0xFF{literal})', f'ITantraColors.{token}')
    text = text.replace('selectedLabelColor = ITantraColors.SurfaceWhite', 'selectedLabelColor = ITantraColors.OnPrimary')
    # Conditional white labels on primary-filled controls.
    for name in ('isHindi', 'isEnglish', 'isAuto', 'isTargetHindi', 'isTargetEnglish', 'isSelected'):
        text = text.replace(f'if ({name}) Color.White', f'if ({name}) ITantraColors.OnPrimary')
    text = text.replace('if (inputText.isNotBlank() && isConnected) Color.White',
                        'if (inputText.isNotBlank() && isConnected) ITantraColors.OnPrimary')
    if text != old:
        assert 'import com.example.itantra.ui.theme.ITantraColors' in text, p
        p.write_text(text, encoding='utf-8')
        print(p.relative_to(ROOT))
