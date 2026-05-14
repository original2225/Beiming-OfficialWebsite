import {
  Cloud,
  KeyRound,
  Mail,
  MessageCircle,
  Network,
  PackageOpen,
  ShieldCheck,
  UsersRound,
} from 'lucide-react';

export const navGroups = [
  {
    title: '资源',
    items: [
      { id: 'resources', label: '资源', icon: PackageOpen },
      { id: 'cloud', label: '云盘', icon: Cloud },
    ],
  },
  {
    title: '平台',
    items: [
      { id: 'network', label: '网络与存储', icon: Network },
      { id: 'identity', label: '用户与绑定', icon: UsersRound },
      { id: 'security', label: '安全审计', icon: ShieldCheck },
    ],
  },
];

export const accounts = [
  { provider: '邮箱', value: 'admin@beiming.dev', status: '已验证', icon: Mail },
  { provider: 'QQ', value: '284****920', status: '已绑定', icon: MessageCircle },
  { provider: 'Daemon Token', value: '本地保存', status: '已配置', icon: KeyRound },
];
