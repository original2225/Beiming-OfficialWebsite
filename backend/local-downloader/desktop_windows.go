//go:build windows

package main

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const (
	statusPanelWidth     = 360
	statusPanelHeight    = 430
	wmDestroy            = 0x0002
	wmActivate           = 0x0006
	wmKillFocus          = 0x0008
	wmCommand            = 0x0111
	wmEraseBkgnd         = 0x0014
	wmPaint              = 0x000F
	wmSetCursor          = 0x0020
	wmKeyDown            = 0x0100
	wmMouseMove          = 0x0200
	wmLButtonUp          = 0x0202
	wmRButtonUp          = 0x0205
	wmTimer              = 0x0113
	wmAppTray            = 0x8001
	wsPopup              = 0x80000000
	wsVisible            = 0x10000000
	wsExTopMost          = 0x00000008
	wsExToolWindow       = 0x00000080
	nimAdd               = 0x00000000
	nimDelete            = 0x00000002
	nifMessage           = 0x00000001
	nifIcon              = 0x00000002
	nifTip               = 0x00000004
	ofnHideReadOnly      = 0x00000004
	ofnNoChangeDir       = 0x00000008
	ofnOverwritePrompt   = 0x00000002
	ofnPathMustExist     = 0x00000800
	ofnExplorer          = 0x00080000
	ofnEnableSizing      = 0x00800000
	idiApplication       = 32512
	idcArrow             = 32512
	smCxScreen           = 0
	smCyScreen           = 1
	swShow               = 5
	swShownormal         = 1
	swRestore            = 9
	vkLButton            = 0x01
	vkRButton            = 0x02
	dtLeft               = 0x00000000
	dtCenter             = 0x00000001
	dtVCenter            = 0x00000004
	dtSingleLine         = 0x00000020
	dtEndEllipsis        = 0x00008000
	diNormal             = 0x0003
	transparent          = 1
	psSolid              = 0
	srcCopy              = 0x00CC0020
	fwNormal             = 400
	fwMedium             = 500
	fwSemiBold           = 600
	waInactive           = 0
	vkEscape             = 0x1B
	idTrayExit           = 1001
	idStatusTimer        = 2001
	hkeyCurrentUser      = 0x80000001
	keySetValue          = 0x0002
	keyCreateSubKey      = 0x0004
	regOptionNonVolatile = 0
	regSz                = 1
	asfwAny              = 0xffffffff
	swpNoSize            = 0x0001
	swpNoMove            = 0x0002
	swpShowWindow        = 0x0040
)

var (
	user32               = syscall.NewLazyDLL("user32.dll")
	shell32              = syscall.NewLazyDLL("shell32.dll")
	kernel32             = syscall.NewLazyDLL("kernel32.dll")
	advapi32             = syscall.NewLazyDLL("advapi32.dll")
	gdi32                = syscall.NewLazyDLL("gdi32.dll")
	comdlg32             = syscall.NewLazyDLL("comdlg32.dll")
	procRegisterClassEx  = user32.NewProc("RegisterClassExW")
	procCreateWindowEx   = user32.NewProc("CreateWindowExW")
	procDefWindowProc    = user32.NewProc("DefWindowProcW")
	procDestroyWindow    = user32.NewProc("DestroyWindow")
	procGetMessage       = user32.NewProc("GetMessageW")
	procTranslateMsg     = user32.NewProc("TranslateMessage")
	procDispatchMsg      = user32.NewProc("DispatchMessageW")
	procPostQuitMessage  = user32.NewProc("PostQuitMessage")
	procLoadIcon         = user32.NewProc("LoadIconW")
	procLoadCursor       = user32.NewProc("LoadCursorW")
	procSetCursor        = user32.NewProc("SetCursor")
	procSetProcessDPI    = user32.NewProc("SetProcessDPIAware")
	procGetCursorPos     = user32.NewProc("GetCursorPos")
	procSetForeground    = user32.NewProc("SetForegroundWindow")
	procSetFocus         = user32.NewProc("SetFocus")
	procShowWindow       = user32.NewProc("ShowWindow")
	procAllowForeground  = user32.NewProc("AllowSetForegroundWindow")
	procBringWindowTop   = user32.NewProc("BringWindowToTop")
	procEnumWindows      = user32.NewProc("EnumWindows")
	procGetClassName     = user32.NewProc("GetClassNameW")
	procIsWindowVisible  = user32.NewProc("IsWindowVisible")
	procSetWindowPos     = user32.NewProc("SetWindowPos")
	procGetAsyncKeyState = user32.NewProc("GetAsyncKeyState")
	procSetTimer         = user32.NewProc("SetTimer")
	procKillTimer        = user32.NewProc("KillTimer")
	procGetSystemMetric  = user32.NewProc("GetSystemMetrics")
	procInvalidateRect   = user32.NewProc("InvalidateRect")
	procBeginPaint       = user32.NewProc("BeginPaint")
	procEndPaint         = user32.NewProc("EndPaint")
	procDrawIconEx       = user32.NewProc("DrawIconEx")
	procSetWindowRgn     = user32.NewProc("SetWindowRgn")
	procShellNotifyIcon  = shell32.NewProc("Shell_NotifyIconW")
	procShellExecute     = shell32.NewProc("ShellExecuteW")
	procExtractIconEx    = shell32.NewProc("ExtractIconExW")
	procGetModuleHandle  = kernel32.NewProc("GetModuleHandleW")
	procRegCreateKeyEx   = advapi32.NewProc("RegCreateKeyExW")
	procRegSetValueEx    = advapi32.NewProc("RegSetValueExW")
	procRegCloseKey      = advapi32.NewProc("RegCloseKey")
	procCreateSolidBrush = gdi32.NewProc("CreateSolidBrush")
	procCreateCompatDC   = gdi32.NewProc("CreateCompatibleDC")
	procCreateCompatBmp  = gdi32.NewProc("CreateCompatibleBitmap")
	procBitBlt           = gdi32.NewProc("BitBlt")
	procDeleteDC         = gdi32.NewProc("DeleteDC")
	procCreatePen        = gdi32.NewProc("CreatePen")
	procSelectObject     = gdi32.NewProc("SelectObject")
	procDeleteObject     = gdi32.NewProc("DeleteObject")
	procRoundRect        = gdi32.NewProc("RoundRect")
	procSetBkMode        = gdi32.NewProc("SetBkMode")
	procSetTextColor     = gdi32.NewProc("SetTextColor")
	procDrawText         = user32.NewProc("DrawTextW")
	procCreateFont       = gdi32.NewProc("CreateFontW")
	procCreateRoundRgn   = gdi32.NewProc("CreateRoundRectRgn")
	procGetSaveFileName  = comdlg32.NewProc("GetSaveFileNameW")
	procCommDlgError     = comdlg32.NewProc("CommDlgExtendedError")
	trayHwnd             uintptr
	statusPanelHwnd      uintptr
	statusPanelBounds    rect
	statusPanelOpenedAt  int64
	statusPanelHover     int
	trayIconData         notifyIconData
)

type point struct {
	X int32
	Y int32
}

type msg struct {
	Hwnd    uintptr
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      point
}

type wndClassEx struct {
	Size       uint32
	Style      uint32
	WndProc    uintptr
	ClsExtra   int32
	WndExtra   int32
	Instance   uintptr
	Icon       uintptr
	Cursor     uintptr
	Background uintptr
	MenuName   *uint16
	ClassName  *uint16
	IconSm     uintptr
}

type notifyIconData struct {
	Size             uint32
	Hwnd             uintptr
	ID               uint32
	Flags            uint32
	CallbackMessage  uint32
	Icon             uintptr
	Tip              [128]uint16
	State            uint32
	StateMask        uint32
	Info             [256]uint16
	TimeoutOrVersion uint32
	InfoTitle        [64]uint16
	InfoFlags        uint32
	GuidItem         [16]byte
	BalloonIcon      uintptr
}

type rect struct {
	Left   int32
	Top    int32
	Right  int32
	Bottom int32
}

type paintStruct struct {
	Hdc       uintptr
	Erase     int32
	RcPaint   rect
	Restore   int32
	IncUpdate int32
	Reserved  [32]byte
}

type openFileName struct {
	StructSize      uint32
	Owner           uintptr
	Instance        uintptr
	Filter          *uint16
	CustomFilter    *uint16
	MaxCustomFilter uint32
	FilterIndex     uint32
	File            *uint16
	MaxFile         uint32
	FileTitle       *uint16
	MaxFileTitle    uint32
	InitialDir      *uint16
	Title           *uint16
	Flags           uint32
	FileOffset      uint16
	FileExtension   uint16
	DefExt          *uint16
	CustData        uintptr
	FnHook          uintptr
	TemplateName    *uint16
	PvReserved      uintptr
	DwReserved      uint32
	FlagsEx         uint32
}

func setupDesktopIntegration() {
	procSetProcessDPI.Call()
	registerProtocol()
	go runTray()
}

func registerProtocol() {
	exe, err := os.Executable()
	if err != nil {
		return
	}
	exe, _ = filepath.Abs(exe)
	command := `"` + exe + `" "%1"`
	setRegistryString(`Software\Classes\beiming-downloader`, "", "URL:Beiming Downloader")
	setRegistryString(`Software\Classes\beiming-downloader`, "URL Protocol", "")
	setRegistryString(`Software\Classes\beiming-downloader\DefaultIcon`, "", exe)
	setRegistryString(`Software\Classes\beiming-downloader\shell\open\command`, "", command)
}

func runTray() {
	runtime.LockOSThread()
	className, _ := syscall.UTF16PtrFromString("BeimingLocalDownloaderTray")
	instance, _, _ := procGetModuleHandle.Call(0)
	icon := loadBeimingIcon()
	cursor, _, _ := procLoadCursor.Call(0, uintptr(idcArrow))
	wc := wndClassEx{
		Size:      uint32(unsafe.Sizeof(wndClassEx{})),
		WndProc:   syscall.NewCallback(trayWndProc),
		Instance:  instance,
		Icon:      icon,
		Cursor:    cursor,
		ClassName: className,
		IconSm:    icon,
	}
	procRegisterClassEx.Call(uintptr(unsafe.Pointer(&wc)))
	hwnd, _, _ := procCreateWindowEx.Call(0, uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(className)), 0, 0, 0, 0, 0, 0, 0, instance, 0)
	if hwnd == 0 {
		return
	}
	trayHwnd = hwnd
	addTrayIcon(hwnd, icon)
	var message msg
	for {
		ret, _, _ := procGetMessage.Call(uintptr(unsafe.Pointer(&message)), 0, 0, 0)
		if int32(ret) <= 0 {
			break
		}
		procTranslateMsg.Call(uintptr(unsafe.Pointer(&message)))
		procDispatchMsg.Call(uintptr(unsafe.Pointer(&message)))
	}
}

func addTrayIcon(hwnd, icon uintptr) {
	trayIconData = notifyIconData{
		Size:            uint32(unsafe.Sizeof(notifyIconData{})),
		Hwnd:            hwnd,
		ID:              1,
		Flags:           nifMessage | nifIcon | nifTip,
		CallbackMessage: wmAppTray,
		Icon:            icon,
	}
	copy(trayIconData.Tip[:], syscall.StringToUTF16("北冥本地下载器 - 云盘多线程直连下载"))
	procShellNotifyIcon.Call(nimAdd, uintptr(unsafe.Pointer(&trayIconData)))
}

func removeTrayIcon() {
	if trayIconData.Hwnd != 0 {
		procShellNotifyIcon.Call(nimDelete, uintptr(unsafe.Pointer(&trayIconData)))
	}
}

func trayWndProc(hwnd uintptr, message uint32, wParam, lParam uintptr) uintptr {
	switch message {
	case wmAppTray:
		if lParam == wmRButtonUp {
			showStatusPanel(hwnd)
			return 0
		}
	case wmCommand:
		if lowWord(wParam) == idTrayExit {
			exitTrayProcess(hwnd)
			return 0
		}
	case wmSetCursor:
		cursor, _, _ := procLoadCursor.Call(0, uintptr(idcArrow))
		procSetCursor.Call(cursor)
		return 1
	case wmKeyDown:
		if hwnd == statusPanelHwnd && wParam == vkEscape {
			closeStatusPanel()
			return 0
		}
	case wmActivate:
		if hwnd == statusPanelHwnd && lowWord(wParam) == waInactive && panelReadyToClose() {
			closeStatusPanel()
			return 0
		}
	case wmKillFocus:
		if hwnd == statusPanelHwnd && panelReadyToClose() {
			closeStatusPanel()
			return 0
		}
	case wmMouseMove:
		if hwnd == statusPanelHwnd {
			x, y := pointFromLParam(lParam)
			nextHover := hoverAtPoint(x, y)
			if nextHover != statusPanelHover {
				statusPanelHover = nextHover
				procInvalidateRect.Call(hwnd, 0, 0)
			}
			return 0
		}
	case wmTimer:
		if hwnd == statusPanelHwnd && wParam == idStatusTimer {
			if shouldCloseStatusPanelByOutsideClick() {
				closeStatusPanel()
				return 0
			}
			procInvalidateRect.Call(hwnd, 0, 0)
			return 0
		}
	case wmEraseBkgnd:
		if hwnd == statusPanelHwnd {
			return 1
		}
	case wmPaint:
		if hwnd == statusPanelHwnd {
			paintStatusPanel(hwnd)
			return 0
		}
	case wmLButtonUp:
		if hwnd == statusPanelHwnd {
			x, y := pointFromLParam(lParam)
			if pointInRect(x, y, downloadsButtonRect()) {
				openCurrentDownloadLocation()
				closeStatusPanel()
			} else if pointInRect(x, y, exitButtonRect()) {
				exitTrayProcess(hwnd)
			}
			return 0
		}
	case wmDestroy:
		if hwnd == statusPanelHwnd {
			procKillTimer.Call(hwnd, idStatusTimer)
			statusPanelHwnd = 0
			return 0
		}
		if hwnd == trayHwnd {
			removeTrayIcon()
			procPostQuitMessage.Call(0)
			return 0
		}
		return 0
	}
	ret, _, _ := procDefWindowProc.Call(hwnd, uintptr(message), wParam, lParam)
	return ret
}

func showStatusPanel(hwnd uintptr) {
	if statusPanelHwnd != 0 {
		procDestroyWindow.Call(statusPanelHwnd)
	}
	procSetForeground.Call(hwnd)
	var cursor point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&cursor)))
	const width = statusPanelWidth
	const height = statusPanelHeight
	x := cursor.X - width + 14
	y := cursor.Y - height - 10
	screenW, _, _ := procGetSystemMetric.Call(smCxScreen)
	screenH, _, _ := procGetSystemMetric.Call(smCyScreen)
	if x < 8 {
		x = 8
	}
	if y < 8 {
		y = cursor.Y + 12
	}
	if int32(screenW) > 0 && x+width > int32(screenW)-8 {
		x = int32(screenW) - width - 8
	}
	if int32(screenH) > 0 && y+height > int32(screenH)-8 {
		y = int32(screenH) - height - 8
	}
	className, _ := syscall.UTF16PtrFromString("BeimingLocalDownloaderTray")
	title, _ := syscall.UTF16PtrFromString("北冥本地下载器")
	instance, _, _ := procGetModuleHandle.Call(0)
	panel, _, _ := procCreateWindowEx.Call(
		wsExTopMost|wsExToolWindow,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(title)),
		wsPopup|wsVisible,
		uintptr(x),
		uintptr(y),
		width,
		height,
		0,
		0,
		instance,
		0,
	)
	if panel == 0 {
		return
	}
	statusPanelHwnd = panel
	statusPanelBounds = rect{x, y, x + width, y + height}
	statusPanelOpenedAt = timeNowMillis()
	statusPanelHover = 0
	procGetAsyncKeyState.Call(vkLButton)
	procGetAsyncKeyState.Call(vkRButton)
	region, _, _ := procCreateRoundRgn.Call(0, 0, width+1, height+1, 18, 18)
	if region != 0 {
		procSetWindowRgn.Call(panel, region, 1)
	}
	procSetTimer.Call(panel, idStatusTimer, 120, 0)
	procShowWindow.Call(panel, swShow)
	procSetForeground.Call(panel)
	procSetFocus.Call(panel)
	procInvalidateRect.Call(panel, 0, 1)
}

func paintStatusPanel(hwnd uintptr) {
	var ps paintStruct
	hdc, _, _ := procBeginPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
	if hdc == 0 {
		return
	}
	defer procEndPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
	paintDC := hdc
	memDC, _, _ := procCreateCompatDC.Call(hdc)
	if memDC != 0 {
		bitmap, _, _ := procCreateCompatBmp.Call(hdc, statusPanelWidth, statusPanelHeight)
		if bitmap != 0 {
			oldBitmap, _, _ := procSelectObject.Call(memDC, bitmap)
			paintDC = memDC
			defer func() {
				procBitBlt.Call(hdc, 0, 0, statusPanelWidth, statusPanelHeight, memDC, 0, 0, srcCopy)
				procSelectObject.Call(memDC, oldBitmap)
				procDeleteObject.Call(bitmap)
				procDeleteDC.Call(memDC)
			}()
		} else {
			procDeleteDC.Call(memDC)
		}
	}
	drawStatusPanel(paintDC)
}

func drawStatusPanel(hdc uintptr) {
	procSetBkMode.Call(hdc, transparent)

	summary := currentTraySummary()
	drawRoundRect(hdc, rect{0, 0, statusPanelWidth, statusPanelHeight}, rgb(255, 255, 255), rgb(226, 232, 240), 18)
	drawRoundRect(hdc, rect{18, 16, 58, 56}, rgb(248, 250, 252), rgb(226, 232, 240), 18)
	if trayIconData.Icon != 0 {
		procDrawIconEx.Call(hdc, 26, 24, trayIconData.Icon, 26, 26, 0, 0, diNormal)
	}

	drawText(hdc, "北冥本地下载器", rect{74, 16, 244, 56}, 19, fwMedium, rgb(15, 23, 42), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
	drawMenuStatus(hdc, summary.State)
	drawSeparator(hdc, 74)
	drawSpeedRow(hdc, 86, summary.Speed, summary.UploadSpeed)
	drawPlainRow(hdc, 136, "线程状态", summary.Threads)
	drawProgressRow(hdc, 190, summary.Percent, summary.PercentValue)
	drawPlainRow(hdc, 244, "当前任务", summary.Task)
	drawSeparator(hdc, 302)
	drawMenuAction(hdc, downloadsButtonRect(), "打开下载位置", "›", statusPanelHover == 1)
	drawSeparator(hdc, 358)
	drawMenuAction(hdc, exitButtonRect(), "退出下载进程", "", statusPanelHover == 2)
}

func drawMenuStatus(hdc uintptr, state string) {
	fill := rgb(240, 253, 244)
	stroke := rgb(187, 247, 208)
	text := rgb(22, 101, 52)
	if state == "下载中" {
		fill = rgb(239, 246, 255)
		stroke = rgb(191, 219, 254)
		text = rgb(29, 78, 216)
	} else if state == "已暂停" || state == "排队中" {
		fill = rgb(255, 251, 235)
		stroke = rgb(253, 230, 138)
		text = rgb(146, 64, 14)
	} else if state == "下载失败" {
		fill = rgb(254, 242, 242)
		stroke = rgb(254, 202, 202)
		text = rgb(185, 28, 28)
	}
	drawRoundRect(hdc, rect{250, 18, 332, 52}, fill, stroke, 17)
	drawText(hdc, state, rect{254, 18, 328, 52}, 14, fwMedium, text, dtCenter|dtVCenter|dtSingleLine|dtEndEllipsis)
}

func drawSpeedRow(hdc uintptr, top int32, downloadSpeed, uploadSpeed string) {
	drawText(hdc, "↓", rect{28, top + 2, 52, top + 38}, 18, fwNormal, rgb(37, 99, 235), dtCenter|dtVCenter|dtSingleLine)
	drawText(hdc, downloadSpeed, rect{68, top + 2, 164, top + 38}, 18, fwNormal, rgb(15, 23, 42), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
	drawText(hdc, "↑", rect{184, top + 2, 208, top + 38}, 18, fwNormal, rgb(20, 184, 166), dtCenter|dtVCenter|dtSingleLine)
	drawText(hdc, uploadSpeed, rect{224, top + 2, 334, top + 38}, 18, fwNormal, rgb(15, 23, 42), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
}

func drawPlainRow(hdc uintptr, top int32, label, value string) {
	drawText(hdc, label, rect{34, top + 3, 146, top + 37}, 17, fwNormal, rgb(51, 65, 85), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
	drawText(hdc, value, rect{154, top + 2, 334, top + 38}, 18, fwMedium, rgb(2, 6, 23), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
}

func drawProgressRow(hdc uintptr, top int32, percent string, value int) {
	drawText(hdc, "完成进度", rect{34, top + 3, 146, top + 37}, 17, fwNormal, rgb(51, 65, 85), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
	drawText(hdc, percent, rect{154, top + 2, 334, top + 26}, 18, fwMedium, rgb(2, 6, 23), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
	drawRoundRect(hdc, rect{154, top + 33, 334, top + 40}, rgb(226, 232, 240), rgb(226, 232, 240), 4)
	if value > 0 {
		if value > 100 {
			value = 100
		}
		width := int32(180 * value / 100)
		drawRoundRect(hdc, rect{154, top + 33, 154 + width, top + 40}, rgb(34, 197, 94), rgb(34, 197, 94), 4)
	}
}

func drawMenuAction(hdc uintptr, r rect, label, suffix string, hovered bool) {
	if hovered {
		drawRoundRect(hdc, rect{8, r.Top + 4, statusPanelWidth - 8, r.Bottom - 4}, rgb(248, 250, 252), rgb(248, 250, 252), 10)
	}
	drawText(hdc, label, rect{34, r.Top, 268, r.Bottom}, 18, fwNormal, rgb(51, 65, 85), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
	if suffix != "" {
		drawText(hdc, suffix, rect{300, r.Top, 330, r.Bottom}, 22, fwNormal, rgb(100, 116, 139), dtCenter|dtVCenter|dtSingleLine)
	}
}

func drawSeparator(hdc uintptr, y int32) {
	drawRoundRect(hdc, rect{18, y, statusPanelWidth - 18, y + 1}, rgb(226, 232, 240), rgb(226, 232, 240), 1)
}

func drawRoundRect(hdc uintptr, r rect, fill, stroke uintptr, radius int32) {
	brush, _, _ := procCreateSolidBrush.Call(fill)
	pen, _, _ := procCreatePen.Call(psSolid, 1, stroke)
	oldBrush, _, _ := procSelectObject.Call(hdc, brush)
	oldPen, _, _ := procSelectObject.Call(hdc, pen)
	procRoundRect.Call(hdc, uintptr(r.Left), uintptr(r.Top), uintptr(r.Right), uintptr(r.Bottom), uintptr(radius), uintptr(radius))
	procSelectObject.Call(hdc, oldBrush)
	procSelectObject.Call(hdc, oldPen)
	procDeleteObject.Call(brush)
	procDeleteObject.Call(pen)
}

func drawText(hdc uintptr, text string, r rect, size int32, weight int32, color uintptr, format uint32) {
	fontName, _ := syscall.UTF16PtrFromString("Microsoft YaHei UI")
	font, _, _ := procCreateFont.Call(
		uintptr(-size),
		0,
		0,
		0,
		uintptr(weight),
		0,
		0,
		0,
		1,
		0,
		0,
		5,
		0,
		uintptr(unsafe.Pointer(fontName)),
	)
	oldFont, _, _ := procSelectObject.Call(hdc, font)
	procSetTextColor.Call(hdc, color)
	textPtr, _ := syscall.UTF16PtrFromString(text)
	procDrawText.Call(hdc, uintptr(unsafe.Pointer(textPtr)), ^uintptr(0), uintptr(unsafe.Pointer(&r)), uintptr(format))
	procSelectObject.Call(hdc, oldFont)
	procDeleteObject.Call(font)
}

func rgb(red, green, blue byte) uintptr {
	return uintptr(uint32(red) | uint32(green)<<8 | uint32(blue)<<16)
}

func exitButtonRect() rect {
	return rect{0, 360, statusPanelWidth, 424}
}

func closeButtonRect() rect {
	return rect{-1, -1, -1, -1}
}

func downloadsButtonRect() rect {
	return rect{0, 304, statusPanelWidth, 358}
}

func closeStatusPanel() {
	if statusPanelHwnd != 0 {
		procDestroyWindow.Call(statusPanelHwnd)
	}
	statusPanelOpenedAt = 0
	statusPanelHover = 0
}

func hoverAtPoint(x, y int32) int {
	if pointInRect(x, y, downloadsButtonRect()) {
		return 1
	}
	if pointInRect(x, y, exitButtonRect()) {
		return 2
	}
	return 0
}

func openDownloadsFolder() {
	dir, err := downloadsDir()
	if err != nil {
		return
	}
	operation, _ := syscall.UTF16PtrFromString("open")
	target, _ := syscall.UTF16PtrFromString(dir)
	procShellExecute.Call(0, uintptr(unsafe.Pointer(operation)), uintptr(unsafe.Pointer(target)), 0, 0, swShownormal)
}

func openCurrentDownloadLocation() {
	summary := currentTraySummary()
	if strings.TrimSpace(summary.Path) != "" {
		if err := revealDownloadedFile(summary.Path); err == nil {
			return
		}
	}
	openDownloadsFolder()
}

func revealDownloadedFile(path string) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	targetPath := strings.TrimSpace(path)
	if targetPath == "" {
		return errSavePathCancelled
	}
	dialogOwner := createSaveDialogOwnerWindow()
	createdDialogOwner := dialogOwner != 0
	if dialogOwner == 0 {
		dialogOwner = trayHwnd
	}
	if dialogOwner != 0 {
		procAllowForeground.Call(uintptr(asfwAny))
		procSetForeground.Call(dialogOwner)
		procSetFocus.Call(dialogOwner)
		if createdDialogOwner {
			defer procDestroyWindow.Call(dialogOwner)
		}
	}
	operation, _ := syscall.UTF16PtrFromString("open")
	explorer, _ := syscall.UTF16PtrFromString("explorer.exe")
	var args *uint16
	if _, err := os.Stat(targetPath); err != nil {
		parent := filepath.Dir(targetPath)
		if parent == "." || parent == "" {
			return err
		}
		args, _ = syscall.UTF16PtrFromString(`"` + parent + `"`)
	} else {
		args, _ = syscall.UTF16PtrFromString(`/select,"` + targetPath + `"`)
	}
	procShellExecute.Call(dialogOwner, uintptr(unsafe.Pointer(operation)), uintptr(unsafe.Pointer(explorer)), uintptr(unsafe.Pointer(args)), 0, swShownormal)
	time.Sleep(260 * time.Millisecond)
	bringExplorerToFront()
	time.Sleep(520 * time.Millisecond)
	bringExplorerToFront()
	return nil
}

func bringExplorerToFront() {
	var explorerWindow uintptr
	callback := syscall.NewCallback(func(hwnd uintptr, lparam uintptr) uintptr {
		visible, _, _ := procIsWindowVisible.Call(hwnd)
		if visible == 0 {
			return 1
		}
		className := make([]uint16, 256)
		length, _, _ := procGetClassName.Call(hwnd, uintptr(unsafe.Pointer(&className[0])), uintptr(len(className)))
		if length == 0 {
			return 1
		}
		name := syscall.UTF16ToString(className[:length])
		if name == "CabinetWClass" || name == "ExploreWClass" {
			explorerWindow = hwnd
			return 0
		}
		return 1
	})
	procEnumWindows.Call(callback, 0)
	if explorerWindow == 0 {
		return
	}
	hwndTopMost := ^uintptr(0)
	hwndNoTopMost := ^uintptr(1)
	flags := uintptr(swpNoMove | swpNoSize | swpShowWindow)
	procShowWindow.Call(explorerWindow, swRestore)
	procSetWindowPos.Call(explorerWindow, hwndTopMost, 0, 0, 0, 0, flags)
	procBringWindowTop.Call(explorerWindow)
	procSetForeground.Call(explorerWindow)
	procSetWindowPos.Call(explorerWindow, hwndNoTopMost, 0, 0, 0, 0, flags)
	procSetForeground.Call(explorerWindow)
}

func chooseDownloadPath(defaultName string) (string, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	dialogOwner := createSaveDialogOwnerWindow()
	createdDialogOwner := dialogOwner != 0
	if dialogOwner == 0 {
		dialogOwner = trayHwnd
	}
	if dialogOwner != 0 {
		if createdDialogOwner {
			defer procDestroyWindow.Call(dialogOwner)
		}
		procSetForeground.Call(dialogOwner)
		procSetFocus.Call(dialogOwner)
	}

	name := safeFileName(defaultName)
	dir, _ := downloadsDir()
	fileBuffer := make([]uint16, 32768)
	nameUTF16 := syscall.StringToUTF16(name)
	copy(fileBuffer, nameUTF16)

	filter := []uint16{'所', '有', '文', '件', 0, '*', '.', '*', 0, 0}
	title, _ := syscall.UTF16PtrFromString("选择下载保存位置")
	initialDir, _ := syscall.UTF16PtrFromString(dir)
	var defExt *uint16
	if ext := strings.TrimPrefix(filepath.Ext(name), "."); ext != "" {
		defExt, _ = syscall.UTF16PtrFromString(ext)
	}

	ofn := openFileName{
		StructSize: uint32(unsafe.Sizeof(openFileName{})),
		Owner:      dialogOwner,
		Filter:     &filter[0],
		File:       &fileBuffer[0],
		MaxFile:    uint32(len(fileBuffer)),
		InitialDir: initialDir,
		Title:      title,
		Flags:      ofnExplorer | ofnEnableSizing | ofnHideReadOnly | ofnNoChangeDir | ofnOverwritePrompt | ofnPathMustExist,
		DefExt:     defExt,
	}
	ok, _, _ := procGetSaveFileName.Call(uintptr(unsafe.Pointer(&ofn)))
	if ok == 0 {
		code, _, _ := procCommDlgError.Call()
		if code == 0 {
			return "", errSavePathCancelled
		}
		return "", syscall.Errno(code)
	}
	return syscall.UTF16ToString(fileBuffer), nil
}

func createSaveDialogOwnerWindow() uintptr {
	className, _ := syscall.UTF16PtrFromString("BeimingLocalDownloaderTray")
	title, _ := syscall.UTF16PtrFromString("北冥下载保存位置")
	instance, _, _ := procGetModuleHandle.Call(0)
	var cursor point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&cursor)))
	hwnd, _, _ := procCreateWindowEx.Call(
		wsExTopMost|wsExToolWindow,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(title)),
		wsPopup|wsVisible,
		uintptr(cursor.X),
		uintptr(cursor.Y),
		1,
		1,
		0,
		0,
		instance,
		0,
	)
	if hwnd != 0 {
		procShowWindow.Call(hwnd, swShow)
	}
	return hwnd
}

func shouldCloseStatusPanelByOutsideClick() bool {
	var cursor point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&cursor)))
	inside := pointInRect(cursor.X, cursor.Y, statusPanelBounds)
	leftState, _, _ := procGetAsyncKeyState.Call(vkLButton)
	rightState, _, _ := procGetAsyncKeyState.Call(vkRButton)
	pressed := leftState&0x8000 != 0 || rightState&0x8000 != 0
	clicked := leftState&0x0001 != 0 || rightState&0x0001 != 0
	if (clicked || pressed) && !inside && panelReadyToClose() {
		return true
	}
	return false
}

func panelReadyToClose() bool {
	return statusPanelOpenedAt > 0 && timeNowMillis()-statusPanelOpenedAt > 250
}

func pointInRect(x, y int32, r rect) bool {
	return x >= r.Left && x <= r.Right && y >= r.Top && y <= r.Bottom
}

func pointFromLParam(value uintptr) (int32, int32) {
	x := int16(value & 0xffff)
	y := int16((value >> 16) & 0xffff)
	return int32(x), int32(y)
}

func timeNowMillis() int64 {
	return time.Now().UnixMilli()
}

func lowWord(value uintptr) uintptr {
	return value & 0xffff
}

func exitTrayProcess(hwnd uintptr) {
	removeTrayIcon()
	procDestroyWindow.Call(hwnd)
	os.Exit(0)
}

func setRegistryString(path string, name string, value string) {
	var key uintptr
	pathPtr, _ := syscall.UTF16PtrFromString(path)
	ret, _, _ := procRegCreateKeyEx.Call(
		hkeyCurrentUser,
		uintptr(unsafe.Pointer(pathPtr)),
		0,
		0,
		regOptionNonVolatile,
		keySetValue|keyCreateSubKey,
		0,
		uintptr(unsafe.Pointer(&key)),
		0,
	)
	if ret != 0 || key == 0 {
		return
	}
	defer procRegCloseKey.Call(key)
	namePtr := uintptr(0)
	if name != "" {
		ptr, _ := syscall.UTF16PtrFromString(name)
		namePtr = uintptr(unsafe.Pointer(ptr))
	}
	data := syscall.StringToUTF16(value)
	procRegSetValueEx.Call(
		key,
		namePtr,
		0,
		regSz,
		uintptr(unsafe.Pointer(&data[0])),
		uintptr(len(data)*2),
	)
}

func loadBeimingIcon() uintptr {
	exe, err := os.Executable()
	if err == nil {
		exePtr, _ := syscall.UTF16PtrFromString(exe)
		var icon uintptr
		count, _, _ := procExtractIconEx.Call(
			uintptr(unsafe.Pointer(exePtr)),
			0,
			uintptr(unsafe.Pointer(&icon)),
			0,
			1,
		)
		if count > 0 && icon != 0 {
			return icon
		}
	}
	fallback, _, _ := procLoadIcon.Call(0, uintptr(idiApplication))
	return fallback
}
