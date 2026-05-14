//go:build !windows

package main

func setupDesktopIntegration() {}

func chooseDownloadPath(defaultName string) (string, error) {
	return uniqueDownloadPath(defaultName)
}

func revealDownloadedFile(path string) error {
	return nil
}

func chooseUploadFiles() ([]uploadFileSelection, error) {
	return nil, errSavePathCancelled
}
