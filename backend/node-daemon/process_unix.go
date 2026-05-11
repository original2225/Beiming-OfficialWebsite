//go:build !windows

package main

import (
	"os/exec"
	"syscall"
)

func setCommandGroup(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

func killCommandTree(cmd *exec.Cmd) {
	if cmd == nil || cmd.Process == nil {
		return
	}
	pgid, err := syscall.Getpgid(cmd.Process.Pid)
	if err == nil {
		_ = syscall.Kill(-pgid, syscall.SIGKILL)
		return
	}
	_ = cmd.Process.Kill()
}
