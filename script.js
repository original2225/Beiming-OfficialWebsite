const SITE_CONFIG = {
  serverName: "Beiming MC",
  serverHost: "play.beimingmc.com",
  serverPort: 25565,
  versionText: "1.20.1 - 1.21.x",
  communityUrl: "#",
  statusApi: "https://api.mcsrvstat.us/3/",
  downloads: [
    {
      title: "推荐客户端整合包",
      text: "包含性能优化、小地图、语音和服务器资源包配置。",
      href: "#",
      tags: ["Java", "Fabric", "推荐"],
    },
    {
      title: "服务器资源包",
      text: "用于主城材质、活动模型和自定义音效。",
      href: "#",
      tags: ["可选", "资源包"],
    },
    {
      title: "地图与地标",
      text: "主城地图、公共设施坐标、交通网和资源世界边界。",
      href: "#",
      tags: ["坐标", "地图"],
    },
    {
      title: "新手手册",
      text: "入服流程、常用指令、领地教程和第一天建议。",
      href: "#",
      tags: ["PDF", "指南"],
    },
  ],
  wiki: [
    {
      category: "guide",
      title: "新手第一天",
      text: "出生点领取基础物资，阅读公告牌，完成白名单确认后再远行建设。",
    },
    {
      category: "command",
      title: "常用传送指令",
      text: "/spawn 回主城，/home 回家，/tpa 请求传送，/warp 查看公共传送点。",
    },
    {
      category: "policy",
      title: "领地与公共工程",
      text: "大型公共建筑需提前报备，跨区域铁路和道路请遵循主干线规划。",
    },
    {
      category: "guide",
      title: "经济系统",
      text: "通过任务、交易、活动和商店获得货币，禁止恶意操纵市场价格。",
    },
    {
      category: "command",
      title: "箱子与权限",
      text: "使用领地权限管理访客、容器、按钮、门和红石交互。",
    },
    {
      category: "policy",
      title: "封禁与申诉",
      text: "申诉请提供游戏 ID、时间、截图或录像，管理员会按日志复核。",
    },
  ],
};

const RULES = {
  basic: [
    "禁止外挂、矿透、自动钓鱼、脚本刷物资和任何破坏公平性的工具。",
    "禁止恶意破坏、盗窃、刷屏、人身攻击和未经允许进入他人领地。",
    "发现漏洞请通过工单提交，不要传播或利用。",
  ],
  build: [
    "主城周边、公共道路、铁路和活动区域禁止私自拆改。",
    "大型机器、刷怪塔和高频红石装置需要避开公共区，并遵守性能限制。",
    "长期烂尾或影响他人通行的建筑，管理员会先通知再处理。",
  ],
  trade: [
    "玩家交易应明码标价，禁止诈骗、恶意囤货和利用 Bug 获利。",
    "公共商店价格可能随版本和供需调整，重大调整会提前公告。",
    "赞助不出售破坏平衡的物品、权限或战斗优势。",
  ],
  appeal: [
    "举报请提供时间、地点、玩家 ID、截图或录像。",
    "封禁申诉会依据聊天记录、区块日志、背包日志和登录记录复核。",
    "管理员处理纠纷时会优先恢复损失，再决定处罚或警告。",
  ],
};

const $ = (selector, parent = document) => parent.querySelector(selector);
const $$ = (selector, parent = document) => [...parent.querySelectorAll(selector)];

function showToast(message) {
  const toast = $("[data-toast]");
  toast.textContent = message;
  toast.classList.add("is-visible");
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => toast.classList.remove("is-visible"), 2200);
}

function renderDownloads() {
  const container = $("[data-downloads]");
  container.innerHTML = SITE_CONFIG.downloads
    .map(
      (item) => `
        <article class="download-card">
          <h3>${item.title}</h3>
          <p>${item.text}</p>
          <div class="download-meta">${item.tags.map((tag) => `<span>${tag}</span>`).join("")}</div>
          <a href="${item.href}">获取</a>
        </article>
      `,
    )
    .join("");
}

function renderWiki(filter = "all", keyword = "") {
  const normalized = keyword.trim().toLowerCase();
  const entries = SITE_CONFIG.wiki.filter((item) => {
    const matchFilter = filter === "all" || item.category === filter;
    const text = `${item.title} ${item.text}`.toLowerCase();
    return matchFilter && (!normalized || text.includes(normalized));
  });

  $("[data-wiki-grid]").innerHTML = entries.length
    ? entries
        .map(
          (item) => `
            <article class="wiki-card">
              <small>${categoryLabel(item.category)}</small>
              <h3>${item.title}</h3>
              <p>${item.text}</p>
            </article>
          `,
        )
        .join("")
    : `<article class="wiki-card"><h3>没有找到相关条目</h3><p>可以把这个主题加入百科待办。</p></article>`;
}

function categoryLabel(category) {
  return {
    guide: "指南",
    command: "指令",
    policy: "制度",
  }[category];
}

function renderRules(tabName) {
  const list = RULES[tabName] || RULES.basic;
  $("[data-rule-content]").innerHTML = `<ul>${list.map((item) => `<li>${item}</li>`).join("")}</ul>`;
}

async function refreshStatus() {
  const label = $("[data-status-label]");
  const dot = $("[data-status-dot]");
  const players = $("[data-status-players]");
  const version = $("[data-status-version]");
  const ping = $("[data-status-ping]");
  const motd = $("[data-status-motd]");

  label.textContent = "正在检测";
  dot.className = "status-dot";

  const target = SITE_CONFIG.serverPort === 25565 ? SITE_CONFIG.serverHost : `${SITE_CONFIG.serverHost}:${SITE_CONFIG.serverPort}`;
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 6000);

  try {
    const response = await fetch(`${SITE_CONFIG.statusApi}${encodeURIComponent(target)}`, {
      signal: controller.signal,
      cache: "no-store",
    });
    const data = await response.json();
    window.clearTimeout(timer);

    if (!data.online) {
      throw new Error("offline");
    }

    label.textContent = "在线";
    dot.classList.add("is-online");
    players.textContent = `${data.players?.online ?? 0} / ${data.players?.max ?? "--"}`;
    version.textContent = data.version || SITE_CONFIG.versionText;
    ping.textContent = data.debug?.ping ? `${data.debug.ping} ms` : "已响应";
    motd.textContent = Array.isArray(data.motd?.clean) ? data.motd.clean.join(" ") : SITE_CONFIG.serverName;
  } catch (error) {
    window.clearTimeout(timer);
    label.textContent = "未连接到状态接口";
    dot.classList.add("is-offline");
    players.textContent = "-- / --";
    version.textContent = SITE_CONFIG.versionText;
    ping.textContent = "未知";
    motd.textContent = "请确认服务器域名或在 script.js 中更换状态接口。";
  }
}

function bindInteractions() {
  $("[data-year]").textContent = new Date().getFullYear();
  $("[data-server-host]").textContent = SITE_CONFIG.serverHost;
  $("[data-server-version]").textContent = SITE_CONFIG.versionText;
  $("[data-community-link]").href = SITE_CONFIG.communityUrl;

  const header = $("[data-header]");
  const nav = $("[data-nav]");
  window.addEventListener("scroll", () => {
    header.classList.toggle("is-solid", window.scrollY > 36);
  });

  $("[data-nav-toggle]").addEventListener("click", () => {
    nav.classList.toggle("is-open");
  });

  $$("[data-nav] a").forEach((link) => {
    link.addEventListener("click", () => nav.classList.remove("is-open"));
  });

  $("[data-copy-ip]").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(SITE_CONFIG.serverHost);
      showToast(`已复制服务器地址：${SITE_CONFIG.serverHost}`);
    } catch (error) {
      showToast("浏览器不允许自动复制，请手动选中服务器地址。");
    }
  });

  $("[data-refresh-status]").addEventListener("click", refreshStatus);

  $$("[data-rule-tab]").forEach((button) => {
    button.addEventListener("click", () => {
      $$("[data-rule-tab]").forEach((tab) => {
        tab.classList.toggle("is-active", tab === button);
        tab.setAttribute("aria-selected", String(tab === button));
      });
      renderRules(button.dataset.ruleTab);
    });
  });

  const search = $("[data-wiki-search]");
  let currentFilter = "all";
  search.addEventListener("input", () => renderWiki(currentFilter, search.value));

  $$("[data-filter]").forEach((button) => {
    button.addEventListener("click", () => {
      currentFilter = button.dataset.filter;
      $$("[data-filter]").forEach((tag) => tag.classList.toggle("is-active", tag === button));
      renderWiki(currentFilter, search.value);
    });
  });
}

renderDownloads();
renderWiki();
bindInteractions();
refreshStatus();
