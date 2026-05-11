//go:build windows

package main

import "os/exec"

func setCommandGroup(_ *exec.Cmd) {
}

func killCommandTree(cmd *exec.Cmd) {
	if cmd == nil || cmd.Process == nil {
		return
	}
	_ = cmd.Process.Kill()
}
