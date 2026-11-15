; Bộ cài Máy Thu cho Windows (Inno Setup). Được build tự động trên GitHub.

#ifndef AppVer
  #define AppVer "1.0.0"
#endif
#ifndef Src
  #define Src "..\out\MayThu"
#endif

[Setup]
AppId={{8C3E5D2A-7B41-4E1F-9A63-2D5B7C9E4F10}
AppName=Máy Thu
AppVersion={#AppVer}
AppVerName=Máy Thu {#AppVer}
AppPublisher=Máy Thu
DefaultDirName={localappdata}\Programs\MayThu
DefaultGroupName=Máy Thu
DisableProgramGroupPage=yes
DisableDirPage=yes
DisableReadyPage=yes
DisableWelcomePage=yes
PrivilegesRequired=lowest
OutputBaseFilename=MayThu-Setup
SetupIconFile=MayThu\app.ico
UninstallDisplayIcon={app}\MayThu.exe
UninstallDisplayName=Máy Thu
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
CloseApplications=force
RestartApplications=no
ShowLanguageDialog=no

[Languages]
Name: "vi"; MessagesFile: "compiler:Default.isl"

[Messages]
SetupAppTitle=Cài đặt Máy Thu
SetupWindowTitle=Cài đặt %1
ButtonBack=< &Quay lại
ButtonNext=&Tiếp tục >
ButtonInstall=&Cài đặt
ButtonCancel=Hủy
ButtonFinish=&Xong
ExitSetupTitle=Thoát cài đặt
ExitSetupMessage=Chưa cài xong. Nếu thoát bây giờ, Máy Thu sẽ chưa được cài vào máy.%n%nVẫn thoát?
WizardSelectTasks=Tùy chọn
SelectTasksDesc=Bạn muốn tạo thêm những gì?
SelectTasksLabel2=Chọn mục bạn muốn rồi bấm Cài đặt.
WizardInstalling=Đang cài đặt
InstallingLabel=Vui lòng chờ trong giây lát, Máy Thu đang được cài vào máy.
FinishedHeadingLabel=Đã cài xong Máy Thu
FinishedLabelNoIcons=Máy Thu đã được cài vào máy.
FinishedLabel=Máy Thu đã được cài vào máy. Từ nay bạn mở app bằng biểu tượng Máy Thu ở màn hình Desktop hoặc menu Start.
ClickFinish=Bấm Xong để đóng trình cài đặt.
ConfirmUninstall=Gỡ bỏ hoàn toàn %1 khỏi máy?
UninstalledAll=Đã gỡ bỏ %1 khỏi máy.

[Tasks]
Name: "desktopicon"; Description: "Tạo biểu tượng Máy Thu trên màn hình Desktop"; GroupDescription: "Lối tắt:"

[Files]
Source: "{#Src}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Máy Thu"; Filename: "{app}\MayThu.exe"
Name: "{autodesktop}\Máy Thu"; Filename: "{app}\MayThu.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\MayThu.exe"; Description: "Mở Máy Thu ngay"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM MayThu.exe"; Flags: runhidden; RunOnceId: "TatMayThu"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "MayThu"; ValueType: none; Flags: uninsdeletevalue dontcreatekey
