#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download Android Weekly issues, extract highlights, and render a local site."""

from __future__ import annotations

import argparse
import html
import json
import re
import socket
import sys
import time
from dataclasses import asdict, dataclass
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable, Optional, Sequence
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlencode, urljoin, urlparse, urlunparse
from urllib.request import Request, urlopen


ARCHIVE_URL = "https://androidweekly.net/archive"
ISSUE_URL_PATTERN = re.compile(r"/issues/issue-(\d+)$")
RETRYABLE_STATUS_CODES = {429, 500, 502, 503, 504}
KEYWORDS = {
    "android": 3, "kotlin": 3, "compose": 3, "jetpack": 3, "gradle": 2,
    "agp": 2, "android studio": 2, "security": 3, "privacy": 2, "release": 2,
    "performance": 2, "testing": 2, "test": 1, "debug": 1, "aosp": 2,
}
DIMENSION_SIGNALS = {
    "novelty": ("new", "novel", "first", "future", "ai", "experimental", "release", "2.0"),
    "depth": ("architecture", "internals", "under the hood", "performance", "compiler", "runtime", "security"),
    "engineering_value": ("build", "testing", "debug", "tooling", "library", "production", "migration", "api"),
    "story": ("how", "why", "what broke", "journey", "lessons", "challenge", "from", "inside"),
}


class CrawlError(RuntimeError):
    """Raised when a source page cannot be fetched or parsed safely."""


@dataclass
class Entry:
    title: str
    url: str
    summary: str
    section: str
    score: int = 0
    topics: list[str] | None = None
    dimensions: dict[str, int] | None = None
    score_reason: str = ""


@dataclass
class Issue:
    number: int
    url: str
    title: str
    published: str
    entries: list[Entry]
    highlights: list[Entry]


@dataclass
class OpenSourceProject:
    name: str
    url: str
    platform: str
    category: str
    topics: list[str]
    score: int
    score_reason: str
    why: str
    practice: str
    interview: str
    occurrences: list[dict[str, str]]


def clean_text(value: str) -> str:
    return " ".join(html.unescape(value).split())


class IssueParser(HTMLParser):
    """Read issue content blocks without coupling to the newsletter's table nesting."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.title = ""
        self.published = ""
        self.section = "Articles"
        self.entries: list[Entry] = []
        self._in_h2 = False
        self._in_small = False
        self._block_depth = 0
        self._block_text: list[str] = []
        self._anchor_url: Optional[str] = None
        self._anchor_text: list[str] = []
        self._anchor_style = ""
        self._block_entry: Optional[tuple[str, str]] = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        attributes = dict(attrs)
        classes = attributes.get("class", "") or ""
        if tag == "h2":
            self._in_h2 = True
        elif tag == "small":
            self._in_small = True
        if tag == "td" and "editor-text" in classes:
            self._block_depth += 1
            if self._block_depth == 1:
                self._block_text = []
                self._block_entry = None
        if tag == "a" and self._block_depth and attributes.get("href", "").startswith(("http://", "https://")):
            self._anchor_url = attributes["href"]
            self._anchor_text = []
            self._anchor_style = attributes.get("style", "") or ""

    def handle_endtag(self, tag: str) -> None:
        if tag == "h2":
            self._in_h2 = False
        elif tag == "small":
            self._in_small = False
        if tag == "a" and self._anchor_url:
            title = clean_text("".join(self._anchor_text))
            if title and "font-size: 16px" in self._anchor_style:
                self._block_entry = (title, self._anchor_url)
            self._anchor_url = None
        if tag == "td" and self._block_depth:
            self._block_depth -= 1
            if not self._block_depth and self._block_entry:
                title, url = self._block_entry
                summary = clean_text("".join(self._block_text)).replace(title, "", 1).strip()
                self.entries.append(Entry(title, url, summary, self.section))

    def handle_data(self, data: str) -> None:
        text = clean_text(data)
        if not text:
            return
        if self._in_h2:
            self.title = text
        elif self._in_small and re.search(r"\d{4}", text):
            self.published = text
        if text in ("Articles & Tutorials", "Libraries & Code", "Tools", "Videos"):
            self.section = text
        if self._block_depth:
            self._block_text.append(" " + text)
        if self._anchor_url:
            self._anchor_text.append(" " + text)


def parse_issue(number: int, url: str, source: str) -> Issue:
    parser = IssueParser()
    parser.feed(source)
    entries = deduplicate_entries(parser.entries) or parse_legacy_entries(source)
    if not entries:
        raise CrawlError("issue #%d did not contain recognisable article links" % number)
    highlights = select_highlights(entries)
    return Issue(number, url, parser.title or "Issue #%d" % number, parser.published, entries, highlights)


def parse_legacy_entries(source: str) -> list[Entry]:
    content = re.search(r'<div class="issue">(.*?)(?:</section>|<footer|$)', source, re.DOTALL)
    if content is None:
        return []
    section = "Articles"
    entries: list[Entry] = []
    for match in re.finditer(r"<(h[2-5]|a)\b([^>]*)>(.*?)</\1>", content.group(1), re.DOTALL | re.IGNORECASE):
        tag, attributes, body = match.groups()
        text = clean_text(re.sub(r"<[^>]+>", " ", body))
        if tag.lower().startswith("h"):
            section = text or section
            continue
        href = re.search(r'\bhref=["\']([^"\']+)["\']', attributes, re.IGNORECASE)
        if href is None or not href.group(1).startswith(("http://", "https://")):
            continue
        classes = re.search(r'\bclass=["\']([^"\']*)["\']', attributes, re.IGNORECASE)
        style = re.search(r'\bstyle=["\']([^"\']*)["\']', attributes, re.IGNORECASE)
        is_article = (
            classes and "article-headline" in classes.group(1)
        ) or (
            style and ("font-size: 14px" in style.group(1) or "#336699" in style.group(1))
        )
        if is_article and text:
            entries.append(Entry(text, href.group(1), "", section))
    return deduplicate_entries(entries)


def deduplicate_entries(entries: Iterable[Entry]) -> list[Entry]:
    result: list[Entry] = []
    seen: set[str] = set()
    for entry in entries:
        if entry.url not in seen:
            seen.add(entry.url)
            result.append(entry)
    return result


PROJECT_HOSTS = {
    "github.com": "GitHub",
    "www.github.com": "GitHub",
    "gitlab.com": "GitLab",
    "www.gitlab.com": "GitLab",
    "codeberg.org": "Codeberg",
    "sourceforge.net": "SourceForge",
}
IGNORED_REPOSITORY_SEGMENTS = {"issues", "pull", "pulls", "releases", "actions", "wiki", "blob", "tree", "commit"}
PROJECT_CATEGORIES = (
    ("Compose/UI", ("compose", "ui", "animation", "widget", "layout", "navigation", "design")),
    ("架构/状态管理", ("architecture", "stateflow", "flow", "coroutine", "navigation", "multiplatform", "mvi")),
    ("性能/工具链", ("performance", "build", "gradle", "compiler", "debug", "tool", "profil", "lint")),
    ("测试/质量", ("test", "testing", "quality", "detekt", "snapshot", "mock", "coverage")),
    ("跨平台", ("kotlin multiplatform", "kmp", "multiplatform", "flutter", "ios", "desktop", "wasm")),
    ("AI/Agent", (" ai ", "agent", "appfunctions", "llm", "model")),
)


def canonical_project_url(value: str) -> Optional[tuple[str, str]]:
    parsed = urlparse(value)
    host = parsed.netloc.lower().split("@")[-1].split(":")[0]
    platform = PROJECT_HOSTS.get(host)
    if platform is None:
        return None
    parts = [part for part in parsed.path.split("/") if part]
    if platform in ("GitHub", "GitLab", "Codeberg") and len(parts) < 2:
        return None
    if platform == "SourceForge" and len(parts) < 2:
        return None
    if platform == "GitLab":
        if "-" in parts:
            parts = parts[:parts.index("-")]
        if len(parts) < 2:
            return None
    elif any(part.lower() in IGNORED_REPOSITORY_SEGMENTS for part in parts[2:]):
        return None
    if platform in ("GitHub", "Codeberg", "SourceForge"):
        parts = parts[:2]
    query = [(key, value) for key, value in parse_qsl(parsed.query) if key.lower() not in {"utm_source", "utm_medium", "utm_campaign", "ref"}]
    return urlunparse(("https", host, "/" + "/".join(parts), "", urlencode(query), "")), platform


def project_category(text: str) -> str:
    lowered = " " + text.lower() + " "
    for category, signals in PROJECT_CATEGORIES:
        if any(signal in lowered for signal in signals):
            return category
    return "Android/Kotlin"


def project_topics(text: str) -> list[str]:
    lowered = text.lower()
    return [keyword for keyword in ("Android", "Kotlin", "Compose", "Gradle", "KMP", "Flutter", "coroutines", "testing", "performance", "AI") if keyword.lower() in lowered]


def extract_open_source_projects(issues: Iterable[Issue]) -> list[OpenSourceProject]:
    grouped: dict[str, dict] = {}
    for issue in issues:
        for entry in issue.entries:
            canonical = canonical_project_url(entry.url)
            if canonical is None:
                continue
            url, platform = canonical
            text = "%s %s" % (entry.title, entry.summary)
            record = grouped.setdefault(url, {"platform": platform, "entries": [], "issues": set()})
            record["entries"].append((issue, entry))
            record["issues"].add(issue.number)
    projects = []
    for url, record in grouped.items():
        issue, first_entry = record["entries"][0]
        texts = " ".join(entry.title + " " + entry.summary for _, entry in record["entries"])
        category = project_category(texts)
        topics = project_topics(texts)
        issue_count = len(record["issues"])
        summary_count = sum(bool(entry.summary) for _, entry in record["entries"])
        score = min(10, 3 + min(2, issue_count) + min(2, len(topics) // 2) + (1 if summary_count else 0) + (1 if len(record["entries"]) > 1 else 0))
        reason = "明确仓库链接 + %d 期出现 + %d 个主题信号" % (issue_count, len(topics))
        repo_name = url.rstrip("/").rsplit("/", 1)[-1]
        why = "来源文章明确指向 %s 项目；其主题为 %s，可作为本地研究入口。" % (repo_name, category)
        practice = "用 1-2 周做一个可验证题：阅读核心模块，补一个小功能或回归测试，记录构建/运行结果；不要先假设项目能力。"
        interview = "从“为什么关注它—读了哪个模块—做了什么小实验—证据是什么—还缺什么验证”展开。"
        projects.append(OpenSourceProject(
            name=repo_name, url=url, platform=record["platform"], category=category,
            topics=topics, score=score, score_reason=reason, why=why, practice=practice,
            interview=interview, occurrences=[
                {"issue": str(issue.number), "title": entry.title, "summary": entry.summary, "url": entry.url}
                for issue, entry in record["entries"]
            ],
        ))
    return sorted(projects, key=lambda project: (-project.score, project.category, project.name.lower()))


def select_highlights(entries: Iterable[Entry], limit: int = 6) -> list[Entry]:
    ranked: list[Entry] = []
    for entry in entries:
        haystack = (entry.title + " " + entry.summary).lower()
        topics = [keyword for keyword in KEYWORDS if keyword in haystack]
        dimensions = score_dimensions(haystack)
        entry.dimensions = dimensions
        entry.score = min(10, round(sum(dimensions.values()) / 4) + min(2, len(topics) // 3))
        if (
            any(signal in haystack for signal in ("ai", "future", "novel", "experimental"))
            and any(signal in haystack for signal in ("how", "what broke", "journey", "lessons", "challenge"))
        ):
            entry.score = 10
        entry.topics = topics
        entry.score_reason = "；".join(
            "%s %d/10" % (label, dimensions[key])
            for key, label in (
                ("novelty", "前沿性"), ("depth", "技术深度"),
                ("engineering_value", "工程价值"), ("story", "故事性"),
            )
        )
        ranked.append(entry)
    return sorted(ranked, key=lambda entry: (-entry.score, entry.title.lower()))[:limit]


def score_dimensions(text: str) -> dict[str, int]:
    """Estimate interview value from signals in the newsletter title and summary."""
    dimensions = {
        name: min(10, 1 + sum(2 for signal in signals if signal in text))
        for name, signals in DIMENSION_SIGNALS.items()
    }
    if any(keyword in text for keyword in ("android", "kotlin", "compose", "jetpack")):
        dimensions["engineering_value"] = min(10, dimensions["engineering_value"] + 2)
    if any(keyword in text for keyword in ("security", "performance", "architecture", "compiler")):
        dimensions["depth"] = min(10, dimensions["depth"] + 2)
    return dimensions


def discover_issues(source: str) -> list[tuple[int, str]]:
    numbers = {int(number) for number in re.findall(r'href=["\']/issues/issue-(\d+)["\']', source)}
    if not numbers:
        raise CrawlError("archive did not contain issue links")
    return [(number, "https://androidweekly.net/issues/issue-%d" % number) for number in sorted(numbers)]


def request_text(url: str, delay: float, timeout: float, retries: int) -> str:
    for attempt in range(retries + 1):
        try:
            request = Request(url, headers={"User-Agent": "jugg-android-weekly-crawler/1.0 (+https://github.com/tencentmusic/jugg)"})
            with urlopen(request, timeout=timeout) as response:
                return response.read().decode("utf-8")
        except HTTPError as error:
            if error.code not in RETRYABLE_STATUS_CODES or attempt == retries:
                raise CrawlError("request for %s failed with HTTP %d" % (url, error.code)) from error
        except (URLError, socket.timeout) as error:
            if attempt == retries:
                reason = getattr(error, "reason", error)
                raise CrawlError("request for %s failed: %s" % (url, reason)) from error
        time.sleep(delay * (attempt + 1))
    raise AssertionError("unreachable")


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def load_or_fetch_issue(number: int, url: str, root: Path, refresh: bool, rebuild: bool, delay: float, timeout: float, retries: int) -> Issue:
    raw_path = root / "raw" / ("issue-%d.html" % number)
    json_path = root / "issues" / ("issue-%d.json" % number)
    if json_path.exists() and not refresh and not rebuild:
        return issue_from_dict(json.loads(json_path.read_text(encoding="utf-8")))
    source = raw_path.read_text(encoding="utf-8") if raw_path.exists() and not refresh else request_text(url, delay, timeout, retries)
    if not raw_path.exists() or refresh:
        atomic_write(raw_path, source)
    issue = parse_issue(number, url, source)
    atomic_write(json_path, json.dumps(asdict(issue), ensure_ascii=False, indent=2) + "\n")
    return issue


def issue_from_dict(data: dict) -> Issue:
    entries = [Entry(**entry) for entry in data["entries"]]
    highlights = [Entry(**entry) for entry in data["highlights"]]
    return Issue(data["number"], data["url"], data["title"], data["published"], entries, highlights)


def page(title: str, body: str) -> str:
    return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title><style>body{font:16px system-ui,sans-serif;max-width:900px;margin:2rem auto;padding:0 1rem;color:#17202a}a{color:#087ea4}article{border:1px solid #dce3e8;padding:1rem;margin:1rem 0;border-radius:.4rem}.meta{color:#566573;font-size:.9rem}.topics{font-size:.85rem;color:#6c3483}</style><main><h1>%s</h1>%s</main></html>""" % (html.escape(title), html.escape(title), body)


def entry_html(entry: Entry) -> str:
    topics = ", ".join(entry.topics or [])
    summary = "<p>%s</p>" % html.escape(entry.summary) if entry.summary else ""
    dimensions = " · ".join("%s %d/10" % (label, (entry.dimensions or {}).get(key, 0)) for key, label in (
        ("novelty", "前沿性"), ("depth", "技术深度"), ("engineering_value", "工程价值"), ("story", "故事性")))
    return '<article><h2><a href="%s" rel="noopener noreferrer">%s</a></h2><p class="meta">%s · 综合评分 %d/10</p><p>%s</p>%s%s%s</article>' % (
        html.escape(entry.url, quote=True), html.escape(entry.title), html.escape(entry.section),
        entry.score, html.escape(dimensions), summary,
        '<p class="topics">Topics: %s</p>' % html.escape(topics) if topics else "",
        '<p class="meta">评分理由：%s</p>' % html.escape(entry.score_reason) if entry.score_reason else "")


def render_interview_page(issues: Iterable[Issue], output: Path) -> None:
    entries = {entry.title: entry for issue in issues for entry in issue.entries}
    references = [
        ("XML to Compose in Production: An Android Journey — Part 2", "主案例：生产迁移、StateFlow、Slot API"),
        ("Why should you always test Compose performance in release?", "解释 debug/release 性能差异与验证边界"),
        ("3 Practical Techniques for Smooth Jetpack Compose UI", "解释主线程重活、协程与卡顿治理"),
        ("A Novel Concurrency Testing Tool that Improved the Kotlin Compiler", "补充并发正确性与系统化测试"),
        ("New Performance Challenges in the Agentic Era for Android Builds - Inaki Villar - droidcon USA 2026", "补充构建性能与交付反馈回路"),
    ]
    reference_html = "".join(
        '<li><a href="%s">%s</a>：%s</li>' % (
            html.escape(entries[title].url, quote=True), html.escape(title), html.escape(reason))
        for title, reason in references if title in entries
    )
    body = """<p class="meta"><strong>使用边界：</strong>这是基于 Android Weekly 真实文章归纳的面试叙事模板，不是用户的个人履历。文中的“我负责”“指标”和“结果”都必须替换为本人真实经历；没有证据就删掉，不要把期刊作者的工作说成自己的。</p>
<h2>项目主题</h2><p><strong>生产环境 XML 到 Jetpack Compose 的渐进式迁移，同时建立状态架构、性能验证和发布回退机制。</strong></p>
<h2>项目背景与目标</h2><p>原有页面由 XML、Fragment 和分散的 UI 状态组成，改动成本高，状态同步和复用边界不清。目标不是一次性重写，而是在不阻塞版本交付的前提下迁移一个真实功能：统一 UI 状态模型，减少重复布局代码，保持交互与无障碍行为，并用可重复的性能、稳定性和发布检查证明迁移没有引入回归。</p>
<h2>个人负责内容（可替换）</h2><p>可按真实经历填写：我负责目标功能拆分、Compose 与旧 View 的边界设计、StateFlow 状态建模、可复用组件和 Slot API 设计；我还负责性能基线、测试矩阵、灰度发布与回退开关。若实际只负责其中一部分，应删去其余内容。</p>
<h2>最难的问题</h2><ol><li>旧页面的状态、生命周期和事件分散在 View、Fragment 与异步回调中，直接迁移会产生重复状态或竞态。</li><li>Compose 的重组和主线程工作让“能显示”不等于“滚动流畅”；debug 结果也不能直接代表 release。</li><li>迁移期间新旧 UI 并存，组件复用、主题、导航、测试和发布回退必须同时成立。</li></ol>
<h2>定位过程</h2><ol><li>先记录旧实现的首帧、滚动帧时间、网络/数据库等待和关键交互失败率，建立可比较基线。</li><li>用日志、Compose 性能工具和最小复现页区分重组过多、主线程重活、布局测量和数据层延迟，不凭体感下结论。</li><li>把状态流转画成输入—状态—UI—副作用链路，检查重复订阅、生命周期取消和并发更新顺序。</li><li>在 release 构建、低端设备和真实数据量下复测，并把结果接入发布门禁。</li></ol>
<h2>关键取舍与方案</h2><ul><li>采用渐进迁移而不是大爆炸重写：旧页面继续可用，新 Compose 内容通过明确边界接入。</li><li>用 StateFlow 表达可观察 UI 状态，事件和副作用单独建模；用 Slot API 让容器控制布局、子组件只提供内容。</li><li>把图片处理、映射、排序等重活移出主线程，使用 ViewModel 协程或 produceState/LaunchedEffect，并避免在 Composable 中重复启动任务。</li><li>性能验证同时覆盖 debug 与 release；发布保留 feature flag 和旧实现回退路径。</li></ul>
<h2>失败尝试</h2><p>第一版把旧回调直接包进 Composable，导致状态重复、重组次数增加；随后又只在 debug 环境比较帧率，结论与 release 不一致。修正方式是先收敛单一状态源，再把异步工作移到生命周期明确的协程边界，并用 release、真实数据和重复运行结果复核。</p>
<h2>性能、稳定性与质量指标（必须替换为真实数据）</h2><p>建议口述：首帧从 <code>[A]</code> 降到 <code>[B]</code>，滚动场景慢帧率从 <code>[C]</code> 降到 <code>[D]</code>；崩溃/ANR、关键交互失败率、测试通过率和回退率分别为 <code>[真实值]</code>。如果没有可靠采样，就说“建立了对比方法但没有宣称具体收益”，不要编造百分比。</p>
<h2>最终结果</h2><p>可口述为：迁移后的功能在新旧实现共存期间保持可发布，状态边界和组件复用方式更清晰；性能结论来自 release 和真实数据验证；出现异常时可以通过开关回退。这里的具体收益、上线范围和时间必须替换成自己的证据。</p>
<h2>2 分钟版</h2><p>我参与过一个生产页面从 XML 到 Compose 的渐进式迁移。难点不是把布局语法改写，而是旧页面的状态、异步回调和生命周期分散，直接迁移会产生重复状态和竞态。我先建立旧版本的首帧、滚动和交互基线，再把状态收敛到 StateFlow，用容器加 Slot API 拆出可复用组件，新旧 UI 通过边界逐步替换。定位卡顿时我没有只看 debug，而是用最小复现区分重组过多和主线程重活，把数据映射、排序等工作放到 ViewModel 协程或受控的 produceState/LaunchedEffect，并在 release、真实数据量和低端设备复测。第一版失败在于把旧回调直接包进 Composable，造成重复订阅，后来通过单一状态源和生命周期取消修正。最终结果和指标要以我的真实数据为准，发布时保留回退开关，保证迁移可验证、可回退。</p>
<h2>5 分钟版</h2><p>可以按“背景—目标—基线—设计—失败—验证—结果—复盘”展开：先解释为什么需要迁移以及不做大爆炸重写；再说明负责范围和可观测指标；然后展示状态流、组件边界和异步任务的生命周期；接着讲第一次方案为什么失败，如何用日志与性能工具定位；最后说明 release 验证、测试矩阵、灰度和回退。面试官追问实现细节时，始终把答案落到状态一致性、主线程预算、发布风险和证据链，不把文章作者的成果冒充个人结果。</p>
<h2>面试追问与参考回答</h2><dl><dt><strong>为什么不一次性重写？</strong></dt><dd>因为风险集中且无法快速回退。渐进迁移允许按功能验证状态、性能和发布行为，旧实现是明确的安全网。</dd><dt><strong>为什么必须测 release？</strong></dt><dd>Compose 编译、优化和运行时配置会改变性能表现，debug 结果不能代表用户构建；应在 release 和真实数据量下复测。</dd><dt><strong>如何证明不是“感觉更快”？</strong></dt><dd>固定设备、数据、操作路径和采样方式，比较首帧、慢帧、CPU/内存、ANR/崩溃和交互失败率，并保留原始结果。</dd><dt><strong>异步任务如何避免竞态？</strong></dt><dd>明确单一状态源、事件与副作用边界，使用生命周期绑定的协程取消旧任务，检查重复订阅和并发更新顺序。</dd><dt><strong>如果上线后回归怎么办？</strong></dt><dd>先通过 feature flag 回退到旧 UI，保留现场和指标，再用最小复现定位；修复经过同一发布门禁后再逐步放量。</dd></dl>
<h2>引用的 Android Weekly 原始文章</h2><ul>%s</ul>""" % reference_html
    atomic_write(output / "interview-template-zh.html", page("Android 面试项目案例：XML 到 Compose 生产迁移", body))


def render_case_library_legacy(issues: Iterable[Issue], output: Path) -> None:
    entries = {entry.title: entry for issue in issues for entry in issue.entries}

    def source(title: str) -> str:
        entry = entries.get(title)
        if entry is None:
            return ""
        return '<a href="%s">%s</a>' % (html.escape(entry.url, quote=True), html.escape(title))

    def case(title: str, story: str, sources: list[str], body: str) -> str:
        links = "".join("<li>%s</li>" % source(item) for item in sources if source(item))
        return "<article><h2>%s</h2><p><strong>为什么有故事性：</strong>%s</p>%s<h3>真实文章来源</h3><ul>%s</ul></article>" % (
            title, story, body, links)

    performance = case(
        "案例一：线上列表卡顿与稳定性告警——从止血到根治",
        "冲突明确：上线后关键列表出现卡顿/ANR 风险，团队需要在用户影响和快速交付之间做取舍；转折是发现“能跑”的实现把重活放在主线程，且 debug 测量掩盖了 release 行为。",
        ["3 Practical Techniques for Smooth Jetpack Compose UI", "Why should you always test Compose performance in release?"],
        """<h3>背景与触发事件</h3><p><strong>[需替换]</strong> 在 <code>[版本/日期]</code>，<code>[页面/场景]</code> 出现 <code>[慢帧/ANR/投诉/监控告警]</code>，影响 <code>[真实用户范围]</code>。</p>
<h3>困难、风险与定位证据</h3><p>不能只凭体感回滚。固定设备、数据量和操作路径，分别采集 release/debug 的首帧、慢帧、CPU、主线程堆栈与关键交互失败率；用最小复现区分重组过多、布局测量、图片解码和数据映射。</p>
<h3>失败尝试与方案取舍</h3><p><strong>[需替换]</strong> 第一轮仅减少 UI 层代码或只在 debug 测试，未解决 release 下的卡顿。最终把排序、映射、图片处理等移出主线程，以 ViewModel 协程或受控的 Compose 副作用承接异步工作；不做全量重写，保留 feature flag 和回退。</p>
<h3>结果指标（只能填真实数据）</h3><p>慢帧率 <code>[A]→[B]</code>、首帧 <code>[A]→[B]</code>、ANR/崩溃 <code>[A]→[B]</code>、回退率 <code>[A]→[B]</code>；无采样时只陈述已建立 release 性能门禁。</p>
<h3>2 分钟口述</h3><p>我处理过 <code>[页面]</code> 上线后的卡顿风险。先止血：通过开关限制高风险路径并保留旧实现；再用 release、真实数据和主线程证据复现，而不是只看 debug。定位后发现 <code>[真实根因]</code>，把重活移出主线程并收敛 UI 副作用。我们没有直接大改架构，而是灰度发布、监控慢帧和交互失败率。最终的指标是 <code>[真实结果]</code>；我复盘后把 release 性能验证纳入发布门禁。</p>
<h3>高频追问</h3><dl><dt>为什么 debug 不能代表线上？</dt><dd>优化、运行时配置和调试开销不同，必须在 release 和真实数据量下验证。</dd><dt>如何避免“优化后更难维护”？</dt><dd>把异步边界放在状态层，Composable 只消费状态；每项优化都保留可比较基线和回退。</dd></dl>""",
    )
    concurrency = case(
        "案例二：异步状态偶发错乱——从无法复现到并发正确性测试",
        "冲突来自偶发性：重复点击、快速返回或网络抖动才会触发状态覆盖，日志不足以稳定复现；转折是把问题从“某个协程 Bug”转为状态顺序与并发调度的可测试契约。",
        ["A Novel Concurrency Testing Tool that Improved the Kotlin Compiler", "XML to Compose in Production: An Android Journey — Part 2"],
        """<h3>背景与触发事件</h3><p><strong>[需替换]</strong> 用户在 <code>[快速切换/重试/返回]</code> 后看到 <code>[旧数据覆盖新数据/加载状态卡住/重复提交]</code>；正常单次操作无法稳定复现。</p>
<h3>困难、风险与定位证据</h3><p>先为每次意图、请求、取消和状态提交增加关联 ID，画出输入—状态—副作用顺序。通过受控调度、延迟注入或重复运行构造交错执行，证明 <code>[真实竞态顺序]</code>，而非猜测线程问题。</p>
<h3>失败尝试与方案取舍</h3><p><strong>[需替换]</strong> 仅加互斥锁会掩盖旧请求回写、降低响应性；仅在 UI 层忽略结果会留下数据层不一致。改为单一状态源、按最新意图取消或丢弃过期结果、明确 reducer/事件顺序，并对关键交错场景加入并发回归测试。</p>
<h3>结果指标（只能填真实数据）</h3><p>受控并发用例覆盖 <code>[数量]</code>，偶发错误复现率 <code>[A]→[B]</code>，关键流转失败率 <code>[A]→[B]</code>；没有基线时只报告新增的确定性测试契约。</p>
<h3>2 分钟口述</h3><p>最难的问题是一个偶发状态错乱：用户快速操作时旧请求覆盖了新状态。我没有先加锁，而是为意图和请求建立关联 ID，并用受控调度复现交错顺序。第一版只在 UI 层过滤结果，问题仍会从数据层回写；最终把状态收敛为单一来源，取消或丢弃过期工作，并把竞态顺序写成回归测试。结果请用我自己的 <code>[真实指标]</code> 替换。</p>
<h3>高频追问</h3><dl><dt>为什么不是所有地方都加 Mutex？</dt><dd>锁解决互斥，不自动表达“哪个结果仍然有效”；要先定义最新意图、取消和提交顺序。</dd><dt>如何测试偶发竞态？</dt><dd>控制调度器、延迟和交错点，断言最终状态与副作用次数，而不是依赖睡眠等待。</dd></dl>""",
    )
    build = case(
        "案例三：Agent 时代构建反馈变慢——模块化与发布门禁治理",
        "冲突是工程效能与质量的拉扯：AI/自动化增加改动频率后，构建排队、无效重建和发布不确定性放大；转折是从“优化一条 Gradle 命令”转为按模块、缓存、验证和回退治理整个反馈回路。",
        ["New Performance Challenges in the Agentic Era for Android Builds - Inaki Villar - droidcon USA 2026", "A case study in Multiplatform library development", "Avoid CI/CD Lock-in — Make Your Builds More Portable"],
        """<h3>背景与触发事件</h3><p><strong>[需替换]</strong> 团队在 <code>[AI 辅助开发/多模块扩张/发布窗口]</code> 后发现 <code>[构建排队、缓存失效、CI 波动、错误发布]</code>，交付反馈从 <code>[真实时长]</code> 恶化到 <code>[真实时长]</code>。</p>
<h3>困难、风险与定位证据</h3><p>按模块和任务采集配置、编译、测试、打包、上传耗时，区分可缓存与不可缓存输入；追踪失败类型和重试，避免把偶发远端抖动误判为 Gradle 性能问题。</p>
<h3>失败尝试与方案取舍</h3><p><strong>[需替换]</strong> 只提升 CI 机器规格或盲目并行可能让成本上涨且放大资源竞争。改为缩小受影响模块、稳定任务输入、缓存正确产物、分层执行快反馈与完整发布验证；发布保留制品追溯、质量门禁和回滚路径。</p>
<h3>结果指标（只能填真实数据）</h3><p>P50/P95 构建时长 <code>[A]→[B]</code>、缓存命中率 <code>[A]→[B]</code>、失败重试率 <code>[A]→[B]</code>、发布回滚耗时 <code>[A]→[B]</code>。无真实数值时只说明指标体系和门禁覆盖范围。</p>
<h3>2 分钟口述</h3><p>我治理过 <code>[项目]</code> 的构建与发布反馈变慢问题。先按模块和任务建立 P50/P95、缓存命中和失败类型基线，发现瓶颈是 <code>[真实证据]</code>，而不是简单的机器不够。我们没有全量并行，而是稳定输入、缩小受影响范围、把快速验证和完整发布分层，并保留制品追溯与回滚。最终用 <code>[真实指标]</code> 验证改善，且没有降低发布质量。</p>
<h3>高频追问</h3><dl><dt>如何证明不是单纯加机器？</dt><dd>比较任务分布、缓存命中和队列等待；若瓶颈是无效重建或输入不稳定，加机器只能掩盖问题。</dd><dt>如何兼顾速度与质量？</dt><dd>快路径只做确定性、低成本检查；发布路径保留完整测试、制品可追溯和可回滚门禁。</dd></dl>""",
    )
    migration = """<article><h2>保留案例：生产 XML → Compose 渐进迁移</h2><p>原有完整案例仍保留在 <a href="interview-template-zh.html">独立页面</a>，适合回答架构迁移、状态建模、性能验证和发布回退。</p></article>"""
    intro = """<p class="meta"><strong>使用边界：</strong>以下是根据 Android Weekly 真实文章整理的面试叙事模板，不是个人履历。事故、职责、指标和结果均为 <strong>[需替换]</strong> 项，必须用自己的日志、监控、代码或发布记录验证；无法证明的内容不要口述为亲身经历。</p><p>每个案例都突出冲突、转折和工程取舍，便于在面试中选择与真实经验最接近的主题。</p>"""
    atomic_write(output / "interview-case-library-zh.html", page("Android 面试案例库", intro + performance + concurrency + build + migration))


def render_case_library_public_examples(issues: Iterable[Issue], output: Path) -> None:
    entries = {entry.title: entry for issue in issues for entry in issue.entries}

    def references(titles: list[str]) -> str:
        return "".join(
            '<li><a href="%s">%s</a></li>' % (html.escape(entries[title].url, quote=True), html.escape(title))
            for title in titles if title in entries
        )

    def story(title: str, opening: str, short: str, long: str, follow_up: str, sources: list[str]) -> str:
        return """<article><h2>%s</h2><h3>面试时怎么开头</h3><p>%s</p><h3>2 分钟口述示范稿</h3><p>%s</p><h3>5 分钟口述示范稿</h3><p>%s</p><h3>面试官追问时怎么答</h3>%s<h3>参考资料</h3><ul>%s</ul></article>""" % (
            title, opening, short, long, follow_up, references(sources))

    performance = story(
        "案例一：线上列表卡顿与稳定性告警——从止血到根治",
        "“我想讲一个我把线上卡顿风险从紧急止血做到长期性能门禁的案例。这个示范稿基于公开文章整理，里面的职责和数字必须替换成我自己的真实记录。”",
        "“当时我负责 <code>[替换为你的页面或模块]</code> 的交付。上线后我们从 <code>[替换为你的监控、客服或测试反馈]</code> 发现列表滚动有明显卡顿，并伴随 <code>[替换为你的 ANR、交互失败或用户影响]</code>。最难的是它在开发机 debug 环境不稳定复现，所以我没有马上重写页面，而是先通过 <code>[替换为你的开关或降级]</code> 止血，保住用户路径。随后我固定设备、数据量和手势，用 release 包采集首帧、慢帧、主线程堆栈和关键交互结果。证据显示问题不是单纯 Compose 本身，而是 <code>[替换为你的真实根因，例如映射、排序、图片处理或重复重组]</code> 占用了主线程。第一次我只减少了 UI 代码，release 指标没有改善，这是一次失败尝试。后来我把重活移到 ViewModel 协程，把 UI 保持为单纯的状态渲染，并收敛副作用触发点。上线采用灰度和回退开关，最终慢帧率从 <code>[替换为你的真实指标]</code> 变为 <code>[替换为你的真实指标]</code>。复盘后，我把 release 性能验证和真实数据量纳入发布门禁。”",
        "“这个项目的背景是 <code>[替换为你的业务背景]</code>。我承担的职责不是泛泛地‘优化性能’，而是负责把性能问题变成可测量、可回退的交付风险管理。触发事件是上线后 <code>[替换为你的告警或现象]</code>，当时风险在于继续改动可能扩大用户影响，而直接回滚会影响 <code>[替换为你的业务目标]</code>。我先做了两件事：第一，用 <code>[替换为你的 feature flag、降级策略或限流]</code> 暂时缩小影响；第二，建立证据链。我在相同设备、相同数据和相同操作路径下分别跑 debug 和 release，记录首帧、慢帧、CPU、主线程调用栈及交互完成率。这样发现 debug 的判断与 release 不一致，真正的瓶颈是 <code>[替换为你的真实根因]</code>。最难的点是不能把所有计算都粗暴塞进后台，否则会造成状态延迟或过期结果覆盖新结果。我的取舍是：数据计算和 IO 离开主线程，状态提交仍通过单一状态源；Composable 不直接启动不可控任务，副作用放在生命周期明确的位置。第一版我只做了局部 UI 优化，指标没有下降，说明假设错了；第二版才按主线程证据拆分任务并减少无效重组。发布时我保留旧路径和监控，对 <code>[替换为你的真实比例]</code> 用户灰度。最后我只会报告我能证明的结果：例如慢帧、ANR、交互失败率从 <code>[替换为你的真实指标]</code> 到 <code>[替换为你的真实指标]</code>；如果没有完整数据，我会如实说已经建立性能基线和门禁，而不会编造提升比例。复盘是，性能优化必须先证明在哪个构建、哪个线程、哪个数据规模下慢，再谈方案。”",
        "<dl><dt><strong>问：为什么不直接把所有工作放到后台？</strong></dt><dd>“因为状态提交和取消语义同样重要。我会把计算移出主线程，但用单一状态源保证过期任务不能覆盖最新意图。”</dd><dt><strong>问：怎么证明优化有效？</strong></dt><dd>“固定设备、数据、操作路径和构建类型，比较 release 的帧指标、主线程证据和关键交互结果；没有真实数字就不虚构。”</dd></dl>",
        ["3 Practical Techniques for Smooth Jetpack Compose UI", "Why should you always test Compose performance in release?"],
    )
    concurrency = story(
        "案例二：异步状态偶发错乱——从无法复现到并发正确性测试",
        "“第二个案例是异步状态偶发错乱。我会把它讲成一次从‘偶现 Bug’到可验证并发契约的过程，而不是简单说加了一把锁。”",
        "“我负责 <code>[替换为你的模块]</code> 的状态流转。触发事件是用户快速执行 <code>[替换为你的操作]</code> 后，旧请求偶尔覆盖新状态。最难的是单次操作几乎不能复现。我的第一反应是加锁，但验证后发现锁只能让执行串行，不能回答旧结果是否还有效，所以这是失败尝试。后来我给意图、请求、取消和状态提交补了关联 ID，用受控调度和延迟复现交错顺序，证据证明 <code>[替换为你的真实竞态]</code>。最终我把状态收敛成单一来源，对新意图取消或丢弃过期任务，并为关键交错补回归测试。上线结果请替换成真实的 <code>[错误率、覆盖用例或故障数]</code>；我的复盘是并发问题要先定义结果有效性，再选择锁、取消还是顺序化。”",
        "“项目背景是 <code>[替换为你的业务链路]</code>，我负责状态建模和异步任务边界。上线或测试阶段出现 <code>[替换为你的具体错误表现]</code>，风险是用户看到不一致数据，甚至发生重复提交。定位时我没有依赖多打日志或 sleep，而是按一次用户意图建立关联 ID，记录请求开始、取消、返回和状态提交；再用可控的调度器/延迟把两个请求交错执行。证据表明 <code>[替换为你的旧任务回写顺序]</code>。一次失败尝试是直接加 Mutex：它降低了并发，却没有表达‘新意图到来后旧结果应失效’，还让响应变慢。我的最终方案是把 UI 状态收敛到单一 reducer 或 StateFlow 来源，把副作用和状态提交分开；新意图会 <code>[替换为取消、版本号比较或丢弃策略]</code>，关键交错作为确定性回归测试。结果部分我只填真实数据，例如复现率从 <code>[替换为你的真实指标]</code> 到 <code>[替换为你的真实指标]</code>，或者新增 <code>[替换为你的用例数]</code> 个并发契约测试。复盘是，竞态不是‘线程多’这么简单，核心是定义最新意图、状态顺序和可验证的最终结果。”",
        "<dl><dt><strong>问：为什么不用 Mutex 解决全部问题？</strong></dt><dd>“Mutex 解决互斥，不定义旧结果是否仍有效；我先定义意图版本和提交规则，再选择取消或序列化。”</dd><dt><strong>问：如何让偶发问题可测？</strong></dt><dd>“控制调度和交错点，断言最终状态与副作用次数，不依赖不稳定的 sleep。”</dd></dl>",
        ["A Novel Concurrency Testing Tool that Improved the Kotlin Compiler", "XML to Compose in Production: An Android Journey — Part 2"],
    )
    build = story(
        "案例三：Agent 时代构建反馈变慢——模块化与发布门禁治理",
        "“第三个案例适合回答工程效能。我会强调自己治理的是反馈回路和发布质量，而不是只把 CI 机器换大。”",
        "“我负责 <code>[替换为你的 Android 工程或 CI 范围]</code>。在 <code>[替换为 AI 辅助开发、多模块增长或发布窗口]</code> 后，构建排队和失败重试明显增加。最难的是不同团队都觉得慢，但原因可能是缓存失效、无效重建、远端波动或测试本身。第一版我尝试 <code>[替换为你的失败尝试，例如盲目并行或升级机器]</code>，成本增加但 P95 没有改善。后来我按模块和任务采集配置、编译、测试、打包和队列时间，证据确认瓶颈是 <code>[替换为你的真实证据]</code>。最终我稳定输入、缩小受影响模块、分离快速验证与完整发布，并保留制品追溯和回滚。结果请用真实 P50/P95、缓存命中率和失败率替换；复盘是速度优化必须和质量门禁一起设计。”",
        "“这个案例的背景是 <code>[替换为你的项目规模和发布节奏]</code>。我的职责包括构建链路观测、模块影响范围、缓存策略和发布门禁。触发事件是 <code>[替换为你的 CI 排队、发布延误或失败现象]</code>，风险不只是不方便，而是开发者为了赶进度绕过验证。定位过程先拆任务：配置、编译、测试、打包、上传和队列等待分别计时，并比较缓存命中与失败类型。这样避免把偶发网络问题误判为 Gradle 性能。失败尝试是 <code>[替换为你的实际做法]</code>，例如盲目增加并行度，结果资源竞争更严重。转折是发现 <code>[替换为你的真实根因，例如输入不稳定或无效重建]</code>。最终方案不是全量优化，而是限制受影响模块、让缓存输入可预测、把快速反馈和完整发布验证分层，并保证制品可追溯、可回滚。上线结果必须用真实 P50/P95、缓存命中、失败重试率和回滚耗时填写：<code>[替换为你的真实指标]</code>。复盘是，在 Agent 提高改动频率后，工程系统要优化的是可信反馈速度，而不是单次命令的表面耗时。”",
        "<dl><dt><strong>问：为什么不直接加机器？</strong></dt><dd>“先看队列等待、任务耗时和缓存命中。若是无效重建或输入不稳定，加机器只会掩盖根因。”</dd><dt><strong>问：如何同时保证速度和质量？</strong></dt><dd>“快路径做确定性、低成本检查；完整发布保留测试、制品追溯和回滚门禁。”</dd></dl>",
        ["New Performance Challenges in the Agentic Era for Android Builds - Inaki Villar - droidcon USA 2026", "A case study in Multiplatform library development", "Avoid CI/CD Lock-in — Make Your Builds More Portable"],
    )
    intro = """<p class="meta"><strong>重要声明：</strong>这是基于公开 Android Weekly 案例整理的<strong>第一人称示范稿</strong>，不是你的个人履历。请先用它练习表达，再把所有 <code>[替换为你的……]</code> 内容改为本人真实职责、日志、代码、发布记录和指标；不能证明的事件与结果必须删除，不能把公开文章作者的经验说成自己的。</p><p>使用方法：面试开始时选一个最接近真实经历的案例，先说 2 分钟版；面试官有兴趣后再展开 5 分钟版和追问。</p>"""
    atomic_write(output / "interview-case-library-zh.html", page("Android 面试案例库：可直接口述的示范稿", intro + performance + concurrency + build + '<article><h2>保留案例：XML → Compose 渐进迁移</h2><p>完整 2/5 分钟示范稿见 <a href="interview-template-zh.html">独立迁移案例页面</a>。</p></article>'))


def render_case_library(issues: Iterable[Issue], output: Path) -> None:
    body = """<p class="meta"><strong>重要声明：</strong>本页基于 Jugg 仓库可验证的代码、测试、文档与提交整理。第一人称只是一份<strong>可直接口述的示范稿</strong>：只有实际参与过对应改动的人才能使用“我负责”；其他读者必须改为“这个项目中实现了”或按自己的真实职责改写。仓库没有线上用户、耗时或故障指标，因此所有业务/上线数字均标为 <code>[需结合你的实际经历补充]</code>，不得虚构。</p>
<h2>项目故事：让 Jugg 增量编译支持 Flutter 与 C++ 外部源码</h2>
<p>Jugg 是 Android Studio/IntelliJ 插件，核心链路是在完整 Gradle 基线之上执行旁路增量编译和部署。本案例的真实功能是：当 Flutter Dart、Flutter assets 或 C/C++ 源码变化时，不再只能整包 Gradle 构建，而是派生当前 variant 的 Gradle task，收集可部署的 `flutter_assets` 和 ABI `.so`，交给已有 asset/native overlay 与部署流程。</p>
<h3>面试时怎么开头</h3><p>“我想讲 Jugg 里一个跨 Gradle、Android native、Flutter 和增量部署边界的改动：我们让 Flutter/C++ 源码变化能够进入 Jugg 的增量编译流程，同时保证多模块、共享源码、多 APK 和 Configuration on Demand 下不会把错误产物部署到设备。以下我会区分代码可确认的事实和需要由我本人经历补充的指标。”</p>
<h3>2 分钟口述示范稿</h3><p>“在 Jugg 这个 Android Studio 插件里，我负责 <code>[需结合你的实际经历确认：负责/参与]</code> Flutter 和 C++ 外部源码增量构建的接入。背景是 Jugg 原本主要处理 Java、Kotlin、资源和 manifest；Dart 或 native 源码变化时，如果仍然走完整 Gradle，反馈速度会退化，但如果直接拿中间目录的 `.so` 又可能部署未 strip 的错误产物。我的目标是复用 Gradle 的真实 task 产物，而不是自己猜构建输出。实现上，`ExternalBuildCompiler` 会先用 `resolveExternalBuilds()` 把一个变更文件重新解析到所有命中的 module/variant/task；这点很重要，因为一个物理共享源码可能同时影响多个 native module。随后 `ExternalBuildTaskRunner` 从当前编译命令安全派生目标 task，并追加 `:juggCollectExternalBuildInfo` 收集器，任务成功后才把 metadata patch 合并回活动项目快照。Flutter 侧收集 `flutter_assets` 与 archive/目录两种 native 输出；C++ 侧只收集本次 invocation 生成的 stripped 输出，绝不回退到 `mergeNativeLibs` 的未 strip 目录。最难的问题是 Configuration on Demand 下 APK owner 的 strip 配置可能没有被配置，所以后来通过完整 Gradle 基线缓存 native strip 配置，并在缺失时明确失败而不是伪造成功。代码层面结果是外部变更可以转为既有 Asset/NativeLib 部署产物，失败会保留明确原因；上线时长、用户影响和个人职责需要用 <code>[需结合你的实际经历补充]</code> 替换。”</p>
<h3>5 分钟口述示范稿</h3><p>“这个故事发生在 Jugg 的增量编译链路。Jugg 的设计前提是保留一次完整 Gradle 构建作为基线，然后把局部变化旁路编译并部署。问题在于 Flutter Dart、Flutter asset 和 C/C++ 不是 Jugg 自己能直接编译的 Kotlin/Java 输入，它们必须由项目的 Gradle task 处理；但简单触发一个 task 还不够，因为我们需要知道当前 variant 的产物路径、归属 APK、是否是可部署的 stripped `.so`，并在多模块或共享源码时保证所有受影响目标都执行。我的职责要按真实经历确认；从仓库可验证的实现看，核心入口是 `ExternalBuildCompiler.doCompile()`。它先解析每个外部输入，任何一个输入找不到 metadata、被删除、目标不支持或 Gradle command 不可派生，整轮都会失败，不允许部分成功后把文件从待编译列表移除。这个取舍是为了避免设备状态只更新一半。随后它按 moduleRoot、variant、taskPath 和类型去重：同一物理文件命中多个模块时，一次 Gradle invocation 执行全部 task，成功后分别收集输出。一次关键转折来自共享 native 源码：只根据文件事件绑定的单个 module 会漏掉另一个 target，所以实现改为每轮在全部 modules 中重新 `resolveExternalBuilds()`，并有 `ExternalBuildFlowTest` 覆盖。另一个关键难点是 native 输出。C++ 的 `merge<Variant>NativeLibs` 不是 APK 实际打包的 stripped 内容，所以 `collectCppArtifacts()` 只接受 collector 本轮生成的 stripped 输出；缺失就提示执行完整 Gradle build 刷新基线。Flutter 则兼容 native output 是目录或 archive 两种版本差异，并拒绝 archive 内路径不安全或重复条目。失败尝试不能被我虚构为个人事故；代码历史可确认的转折是提交 `ac77c75d9` 修复共享源码漏 native 输出，`5605fd3bc` 修复 Configuration on Demand 下 C++ 增量变更不生效。后者通过缓存 APK owner 的 strip task 配置，避免在 CoD 模式下额外配置 owner task。最终可确认的结果是：外部产物以既有 `CompileOutput.Asset`/`NativeLib` 进入 staging 和部署，metadata 采用 invocation-scoped collector 原子更新，任何契约缺失都明确失败或回退。构建耗时、覆盖率、线上稳定性和我具体负责部分必须填 <code>[需结合你的实际经历补充]</code>；我不会把代码提交或测试数量描述成线上指标。”</p>
<h3>最难点、证据与取舍</h3><ul><li><strong>共享源码：</strong>证据是同一物理 source 可命中多个 module target；实现按四元键去重 task、但分别收集每个模块输出。</li><li><strong>产物真实性：</strong>C++ 只接收 invocation 级 stripped 输出，拒绝将 merge 目录作为 fallback；Flutter archive 会检查 `lib/&lt;abi&gt;/*.so` 路径和重复项。</li><li><strong>metadata 一致性：</strong>collector request/result 按 invocation ID 与请求 key 严格比对；缺少、重复或不完整结果失败，不用旧监控范围伪造成功。</li><li><strong>Configuration on Demand：</strong>APK owner 未配置时优先用完整基线发布的 native strip cache，实时读取仍失败则保留最终错误。</li></ul>
<h3>代码层面可确认的结果</h3><p>可确认：外部 Flutter/C++ 输入被纳入 `ExternalBuildSource`；Gradle task 产物进入 Jugg 既有 asset/native 部署链；取消、10 分钟超时、非零退出、collector 结果不匹配、输出缺失和不安全 archive 都有明确失败路径。不可从仓库确认：真实项目构建耗时、线上用户影响、故障率和个人贡献比例，统一写为 <code>[需结合你的实际经历补充]</code>。</p>
<h3>面试官追问时怎么答</h3><dl><dt><strong>问：为什么不直接扫描 native 中间目录？</strong></dt><dd>“因为 merge 目录可能是未 strip 的中间输出，和 APK 实际打包内容不一致。Jugg 的实现只信任本次 Gradle invocation 生成的 stripped 输出；拿不到就明确要求完整 Gradle 基线，而不是部署可能错误的库。”</dd><dt><strong>问：共享 C++ 文件变化如何避免漏编译？</strong></dt><dd>“不能依赖文件事件最初挂靠的 module。每轮都在当前全部 module metadata 中重新解析该 source，收集所有命中 target，再按 module、variant、task 和类型去重执行。”</dd><dt><strong>问：为什么 collector 结果要严格匹配请求？</strong></dt><dd>“metadata 是下一轮监控和产物归属的依据。部分结果或旧结果会让下一轮看似成功却漏掉输入，所以 request key、invocation ID 和更新集合必须一一对应。”</dd><dt><strong>问：如何证明结果？</strong></dt><dd>“我会先说代码层可确认的 flow tests 和失败边界；若我参与过实际项目，再补充自己的构建时长、命中率、日志或发布记录。没有这些证据就不报数字。”</dd></dl>
<h3>项目代码依据</h3><ul><li><code>main/src/main/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildCompiler.kt</code>：解析所有外部目标、执行任务、收集 Flutter assets/`.so` 与 C++ stripped `.so`。</li><li><code>main/src/main/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildTaskRunner.kt</code>：安全派生命令、附加 invocation-scoped collector、处理取消/超时/非零退出及结果匹配。</li><li><code>idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt</code>：IDE 增量/Gradle 回退编排与外部输入预检入口。</li><li><code>main/src/test/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildFlowTest.kt</code>：外部构建 flow 回归；<code>ExternalBuildTaskRunnerTest.kt</code>：派生命令与收集器契约；<code>GradleProjectInfoReaderManagerNativeStripTest.kt</code>：native strip 配置读取。</li><li><code>docs/ai_knowledge/02_compile_core.md</code>：外部构建、shared source、stripped output、CoD 和失败边界的实现说明。</li><li>提交：<code>9a7e81031</code> 新增 Flutter/C++ 增量构建；<code>ac77c75d9</code> 修复共享源码漏 native 输出；<code>5605fd3bc</code> 修复 Configuration on Demand 下 C++ 变更不生效。</li></ul>
<h3>参考资料（技术背景，不是 Jugg 项目来源）</h3><p>Android Weekly 的公开文章仅用于性能、并发和构建领域背景；Jugg 案例的事实依据以上述代码、测试、文档和提交为准。</p>"""
    atomic_write(output / "interview-case-library-zh.html", page("Jugg 面试项目案例：Flutter/C++ 外部源码增量构建", body))


def render_open_source_intelligence(issues: Iterable[Issue], output: Path) -> None:
    projects = extract_open_source_projects(issues)
    selected = projects[:50]
    grouped: dict[str, list[OpenSourceProject]] = {}
    for project in selected:
        grouped.setdefault(project.category, []).append(project)
    sections = []
    for category, category_projects in grouped.items():
        cards = []
        for project in category_projects:
            occurrences = "".join(
                "<li>Issue #%s：%s — %s</li>" % (
                    html.escape(item["issue"]), html.escape(item["title"]),
                    html.escape(item["summary"] or "原文未提供摘要"))
                for item in project.occurrences[:8]
            )
            cards.append(
                "<article><h3><a href=\"%s\">%s</a></h3><p class=\"meta\">%s · %s · 情报评分 %d/10</p>"
                "<p><strong>为什么值得关注：</strong>%s</p><p><strong>能做什么：</strong>来源只证明它被 Android Weekly 作为项目/仓库链接收录；具体能力需阅读仓库确认。</p>"
                "<p><strong>1-2 周二次开发/实践题（建议）：</strong>%s</p>"
                "<p><strong>面试技术切入点（建议）：</strong>%s</p><details><summary>出现期号与原始文章</summary><ul>%s</ul></details>"
                "</article>" % (
                    html.escape(project.url, quote=True), html.escape(project.name),
                    html.escape(project.platform), html.escape(", ".join(project.topics) or "未从本地摘要识别主题"),
                    project.score, html.escape(project.why), html.escape(project.practice),
                    html.escape(project.interview), occurrences))
        sections.append("<h2>%s</h2>%s" % (html.escape(category), "".join(cards)))
    body = """<p class="meta"><strong>边界：</strong>本页是 Android Weekly 本地文章情报库，不是 Jugg 项目经历，也不代表用户参与过这些开源项目。项目名称、链接、出现期号和文章摘要来自本地缓存；“能做什么”只在来源明确时陈述，其余均标为建议。当前没有执行 GitHub/GitLab API 查询，网络调用 0，避免把仓库元数据猜测写成事实。</p>
<h2>识别与评分方法</h2><p>候选仅来自明确的 GitHub/GitLab/Codeberg/SourceForge 仓库链接，去除 issue、pull、release、blob 等页面并规范化协议、路径和追踪参数。评分是可解释启发式：明确仓库链接 3 分，多期出现最多 2 分，主题信号最多 2 分，有文章摘要 1 分，重复收录 1 分，满分 10 分。选取前 50 个用于研究页；JSON 保留全部识别候选和每次出现记录。</p>
<h2>可实施项目建议</h2><p>建议优先挑选一个高分项目做 1-2 周阅读与小实验：先固定 commit，画模块/数据流，运行已有测试，再只实现一个可回滚的小改动。以下内容是研究建议，不是用户经历。</p>
%s""" % "".join(sections)
    atomic_write(output / "opensource-projects-zh.html", page("Android Weekly 开源项目情报库", body))
    atomic_write(output / "opensource-projects.json", json.dumps({
        "source": "local Android Weekly issue JSON",
        "network_calls": 0,
        "metadata_queries": [],
        "candidate_count": len(projects),
        "selected_count": len(selected),
        "categories": {category: len(items) for category, items in grouped.items()},
        "projects": [asdict(project) for project in projects],
    }, ensure_ascii=False, indent=2) + "\n")


HARMONYOS_RETRIEVED_ON = "2026-09-19"
HARMONYOS_SOURCES = {
    "ArkTS/类型安全": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/arkts-get-started",
    "ArkUI/状态管理": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/arkts-state-management-overview",
    "组件生命周期": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/arkui-page-custom-components-lifecycle",
    "Stage/UIAbility": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/uiability-lifecycle",
    "Want/路由": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/want-overview",
    "并发": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/taskpool",
    "网络/存储": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/http-request",
    "权限/安全": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/permission-request-result",
    "性能/内存": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/performance-analysis",
    "调试/测试": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-debugging",
    "分布式能力": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/device-management",
    "工程化": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/har-package",
    "Android迁移": "https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/arkts-get-started",
}

HARMONYOS_TOPIC_NOTES = {
    "ArkTS/类型安全": ("ArkTS 以 TypeScript 语法为基础，但面向应用开发加入了受限、静态化的类型规则。我的做法是把领域模型、空值和边界数据在编译期说清楚，不把 any 当作迁移捷径。", "回答时要区分“语法相近”和“类型规则相同”：以当前 SDK 的 ArkTS 约束及编译报错为准，第三方 TypeScript 写法不能直接假定兼容。", "把 ArkTS 说成完整 TypeScript 或说所有 TS 库可直接复用。", "遇到现有 TS 代码时如何迁移？先收紧边界类型、消除隐式 any，再逐个验证 ArkTS 语言规则与 SDK API。"),
    "ArkUI/状态管理": ("ArkUI 是声明式 UI：界面是状态的函数。局部状态、父子同步和跨组件共享状态要按作用域选择机制，状态变化后让框架驱动 UI 更新。", "我会先画状态所有权：页面临时状态留在组件内，业务状态放到明确的状态拥有者；避免多个可写副本，否则刷新顺序会变成偶发问题。", "把任何数据都做成全局可变状态，或在 build 中执行网络、写库等副作用。", "如何处理复杂页面？按状态所有者切分组件，将异步结果归一为加载、成功、空、失败等可渲染状态。"),
    "组件生命周期": ("自定义组件生命周期用于建立、更新和销毁阶段的资源管理；UI 描述仍应保持无副作用，监听、订阅和释放要与生命周期匹配。", "我会把一次性初始化、参数变化响应和资源释放分开，并验证重复进入、组件复用和返回前台时不会重复注册。", "把生命周期回调当成每次重绘都会执行，或在其中遗留无法释放的订阅。", "如何防泄漏？为每个订阅建立对称的注销路径，并在真机调试中观察页面反复进出后的实例与回调数量。"),
    "Stage/UIAbility": ("Stage 模型以 UIAbility 承载界面能力，生命周期回调处理创建、前后台切换和销毁等边界。业务不能只依赖单次启动，要能在恢复和重新创建时恢复正确状态。", "我会把短生命周期 UI 资源和可恢复业务状态分开：前者随 Ability 管理，后者通过受控持久化或状态恢复处理，并以官方生命周期顺序为准。", "把 UIAbility 简单等同 Android Activity，或假设 onCreate 之后不会因系统回收而重新进入。", "冷启动与恢复怎么设计？入口参数解析幂等、状态恢复可失败降级、敏感数据不写入不安全持久层。"),
    "Want/路由": ("Want 是 Ability 间传递目标、动作和参数的载体；页面路由与 Ability 跳转应分别管理。参数必须校验，调用结果和返回路径都要有失败分支。", "我的回答会先说明边界：同一页面栈内的导航不等于跨 Ability 调用；跨边界传参要最小化，并按类型、来源和权限校验。", "把 Want 当成任意对象容器，或将页面路由、跨 Ability 调用和外部拉起混为一谈。", "如何防止参数被滥用？采用白名单路由、强类型解析、缺失值安全处理，并在敏感动作前复核权限。"),
    "并发": ("耗时计算不能阻塞 UI；TaskPool/Worker 等并发机制应按任务隔离、数据传递和取消语义选择。关键不是“开线程”，而是结果何时仍有效。", "我会为每次用户意图建立任务归属和取消/过期策略，后台只做计算或 IO，回到状态层时检查任务版本，避免旧结果覆盖新结果。", "认为 Promise 或 async 一定不占用主线程，或用共享可变对象跨线程传递而不定义同步边界。", "任务失败怎么办？区分可重试的网络/临时失败与业务失败，有限重试并将最终状态显式回传 UI。"),
    "网络/存储": ("网络请求要有超时、错误分类、取消和数据校验；存储按数据敏感度、生命周期和一致性需求选择，而不是把响应原样长期落盘。", "我会把 DTO 校验、缓存策略和 UI 状态分层。缓存命中不能掩盖过期或解析失败，敏感数据遵循最小化存储和加密/权限要求。", "只判断 HTTP 成功码、不处理超时和解析异常，或把 token、隐私数据直接写入普通偏好存储。", "离线策略怎么定？明确可展示的陈旧窗口、刷新时机、写入原子性和用户可见的失败状态。"),
    "权限/安全": ("权限遵循最小化与运行时授权原则：先确认声明和可用性，再在需要时向用户解释并请求；拒绝和永久不可用都要有可用降级。", "我会把权限视为异步业务状态，而不是一次 if。敏感 API 调用前再次校验，收集目的、范围和保存期限与隐私说明一致。", "只在应用启动时批量申请，或把用户拒绝当作异常后继续调用敏感 API。", "如何设计拒绝后的体验？解释功能影响，提供不需要该权限的路径；不要循环弹窗，也不能绕过系统授权。"),
    "性能/内存": ("性能优化先以工具证据定位：区分启动、布局/渲染、CPU、IO 和内存；先修真实热点，再用相同场景复测，不凭主观做大改。", "声明式 UI 中我重点看状态粒度、无效刷新、列表复用和主线程工作；内存问题则看对象生命周期、缓存上限和资源释放。", "看到卡顿就全量缓存或强行拆线程；没有基线就宣称性能提升。", "如何证明有效？固定设备、版本、数据量和操作路径，比较前后 trace/指标，并报告没有改善的项目。"),
    "调试/测试": ("调试以可复现路径、日志、断点和性能工具建立证据；测试分层覆盖纯逻辑、组件交互和关键端到端流程。", "我会先最小复现并记录版本、设备和入口参数，再定位到状态、生命周期或平台边界；修复后补稳定断言而不是只增加日志。", "把真机手测一次当作完整回归，或让测试依赖固定延时和网络时序。", "如何测异步？控制调度和输入，断言最终 UI 状态与副作用次数，而非 sleep 等待。"),
    "分布式能力": ("多设备协同要先确认设备发现、连接、授权和能力可用性，再设计一致性、断连和隐私边界。分布式不是默认可靠网络。", "我会把本地状态与同步状态区分，并为离线、重复事件、设备切换和权限变化定义用户可见的降级；具体 API 以目标 SDK 官方文档为准。", "把“分布式”说成自动同步所有数据，忽略设备能力、用户授权、网络和数据冲突。", "冲突怎么处理？先定义数据主从或版本规则；关键操作采用幂等 ID 和可追踪事件，无法安全合并时提示用户。"),
    "工程化": ("应用制品、模块边界和构建任务需按当前 SDK 的 HAP/HAR 与 HVigor 规则组织。HAR 适合复用代码/资源边界，最终应用入口和打包策略由工程配置决定。", "我会从依赖方向、公共 API、构建耗时和发布制品四个维度拆模块；先稳定边界再拆分，避免为“模块化”制造循环依赖。", "把 HAP、HAR 的职责和最终安装制品混为一谈，或把 Gradle/AGP 配置经验直接套到 HVigor。", "迁移构建脚本的原则？先以官方工程模板和当前版本配置为基线，逐项迁移并在每一步验证构建产物。"),
    "Android迁移": ("Android 经验可迁移的是分层、状态、线程、网络、安全和测试方法；不能直接迁移的是组件、构建、生命周期和平台 API 的具体契约。", "我会先做能力清单和最小可运行垂直切片，再把 UI、导航、存储、权限逐层替换；每层以 HarmonyOS 官方 SDK 的当前版本事实验证。", "声称 Android XML、Activity、Gradle 或 Java API 可以一比一平移。", "首个迁移里程碑是什么？选一个低风险核心流程，验证 ArkTS、ArkUI、路由、网络、存储、权限和构建闭环，再扩大范围。"),
}

HARMONYOS_OPEN_SOURCE_PROJECTS = [
    ("ArkUI ACE Engine", "ArkUI/状态管理", "https://github.com/openharmony/arkui_ace_engine", "Apache-2.0", "ArkUI 框架与渲染相关实现", "README 与 frameworks 目录", "为一个受控状态变化补充最小可验证示例或测试。"),
    ("ArkCompiler ETS Runtime", "ArkTS/类型安全", "https://github.com/openharmony/arkcompiler_ets_runtime", "Apache-2.0", "ETS/ArkTS 运行时与编译执行相关实现", "README 与 runtime 目录", "从官方构建说明出发复现一个诊断路径，不修改运行时语义。"),
    ("Ability Runtime", "Stage/UIAbility", "https://github.com/openharmony/ability_ability_runtime", "Apache-2.0", "Ability 与应用模型运行时能力", "README 与 services 目录", "梳理一个 Ability 生命周期或 Want 流转的调用边界。"),
    ("Distributed Data Management", "分布式能力", "https://github.com/openharmony/distributeddatamgr_appdatamgr", "Apache-2.0", "应用数据管理与分布式数据相关能力", "README 与 interfaces 目录", "为断连、冲突或幂等策略写一份可执行的设计验证清单。"),
    ("Application Samples", "调试/测试", "https://github.com/openharmony/applications_app_samples", "NOASSERTION", "官方示例应用集合", "README 与具体 sample 目录", "选择一个目标 SDK 可运行的示例，增加一个独立页面或测试并记录验证。"),
    ("ArkXTest", "调试/测试", "https://github.com/openharmony/testfwk_arkxtest", "Apache-2.0", "OpenHarmony 测试框架相关项目", "README 与 framework 目录", "为一个最小业务流程设计稳定断言，避免用固定 sleep。"),
]


def harmonyos_interview_data() -> dict:
    questions = []
    for topic, note in HARMONYOS_TOPIC_NOTES.items():
        for suffix, difficulty in (("基础原理与边界是什么？", "中级"), ("在真实项目中如何设计并验证？", "高级")):
            questions.append({
                "id": "q-%02d" % (len(questions) + 1), "topic": topic,
                "question": "%s：%s" % (topic, suffix), "difficulty": difficulty,
                "tags": topic.split("/") + ["原创归纳", "中高级"],
                "standard_answer": note[0], "deep_dive": note[1],
                "pitfall": note[2], "follow_up": note[3],
                "official_sources": [{"title": "HarmonyOS 官方：%s" % topic, "url": HARMONYOS_SOURCES[topic]}],
            })
    scenario_titles = [
        "页面首帧慢且列表滚动掉帧，如何从证据到修复？", "切换页面后旧网络结果覆盖新状态，如何定位？",
        "UIAbility 前后台切换后数据丢失，如何设计恢复？", "深链携带异常参数导致崩溃，如何加固？",
        "权限被拒绝后功能不可用，如何给出降级体验？", "大文件解析造成界面冻结，如何拆分任务？",
        "多设备同步出现重复更新，如何保证幂等？", "离线缓存与服务端新数据冲突，如何取舍？",
        "ArkTS 迁移时类型错误大量出现，如何分阶段收敛？", "复杂页面状态互相覆盖，如何重构所有权？",
        "内存持续增长但未崩溃，如何建立排查路径？", "发布包构建时间翻倍，如何定位 HVigor 任务瓶颈？",
        "HAR 组件升级后调用方崩溃，如何维护兼容性？", "线上崩溃仅在特定设备出现，如何缩小范围？",
        "请求偶发超时，如何区分网络、服务端和客户端问题？", "声明式列表更新频繁，如何控制无效刷新？",
        "Android 页面迁移后返回栈行为不同，如何验收？", "敏感数据要跨页面使用，如何控制暴露面？",
        "分布式设备断连后操作重复提交，如何设计恢复？", "测试偶发失败，如何去掉时间依赖？",
        "多个团队共用组件库，如何治理版本与发布？", "性能优化后体验无改善，如何复盘错误假设？",
    ]
    scenario_topics = [
        "性能/内存", "并发", "Stage/UIAbility", "Want/路由", "权限/安全", "并发",
        "分布式能力", "网络/存储", "ArkTS/类型安全", "ArkUI/状态管理", "性能/内存", "工程化",
        "工程化", "调试/测试", "网络/存储", "ArkUI/状态管理", "Android迁移", "权限/安全",
        "分布式能力", "调试/测试", "工程化", "性能/内存",
    ]
    scenarios = [{
        "id": "s-%02d" % (index + 1), "question": title, "difficulty": "高级",
        "answer_framework": "先复现并固定版本、设备、输入和时间线；采集日志/trace/状态证据，区分 UI、生命周期、并发、网络与平台边界；提出可回退的最小修复；用同一场景复测，并说明无法确认的 SDK/API 细节需查当前官方文档。",
        "follow_up": "如果证据与假设冲突怎么办？停止扩大改动，保留反证，替换假设后再做最小验证。",
        "topic": scenario_topics[index], "source_topics": [scenario_topics[index]],
        "official_sources": [{"title": "HarmonyOS 官方：" + scenario_topics[index], "url": HARMONYOS_SOURCES[scenario_topics[index]]}],
    } for index, title in enumerate(scenario_titles)]
    source_questions = []
    for name, topic, url, license_name, summary, entry, practice in HARMONYOS_OPEN_SOURCE_PROJECTS:
        for prompt in ("你会从哪里开始阅读 %s，并如何验证理解？" % name,
                       "%s 的模块边界如何影响应用侧设计？" % name,
                       "围绕 %s，如何设计一个不依赖猜测的二次开发练习？" % name,
                       "面试中如何说明 %s 的版本与许可证风险？" % name):
            source_questions.append({
                "id": "o-%02d" % (len(source_questions) + 1), "topic": topic, "type": "开源源码题",
                "question": prompt, "difficulty": "高级", "tags": ["开源源码", "原创归纳", topic],
                "standard_answer": "我先从项目 README 和公开目录入口确认项目范围，再沿一个可运行/可测试路径阅读；不根据仓库名推断内部行为。应用侧只引用可验证的公开契约。",
                "deep_dive": "这个项目的公开定位是“%s”。我会从“%s”进入，固定目标分支或提交，记录实际观察到的模块、构建方式和测试入口；结论与当前版本绑定。" % (summary, entry),
                "pitfall": "未阅读目标版本源码就把实现细节、性能数字或所有权说成事实；也不能忽略许可证和分支差异。",
                "follow_up": "练习方案：%s 先在目标 SDK/工具链验证可运行性，失败也要记录环境、错误和停止边界。" % practice,
                "official_sources": [{"title": "%s 项目主页（许可证：%s）" % (name, license_name), "url": url}],
            })
    questions.extend(source_questions)
    return {
        "title": "HarmonyOS / 鸿蒙中高级面试题库", "retrieved_on": HARMONYOS_RETRIEVED_ON,
        "disclaimer": "官方文档是技术事实依据；社区来源只提供高频题型信号；问题、答案和解析均为原创归纳。API、SDK 版本和可用设备能力可能变化，面试前必须以目标 SDK 的官方文档和真机验证为准。",
        "questions": questions, "scenarios": scenarios, "open_source_projects": [
            {"name": name, "topic": topic, "url": url, "license": license_name, "summary": summary, "entry": entry, "practice": practice}
            for name, topic, url, license_name, summary, entry, practice in HARMONYOS_OPEN_SOURCE_PROJECTS
        ],
        "android_quick_compare": [
            ["语言", "Kotlin/Java 经验可迁移", "ArkTS 规则以当前 SDK 编译器为准，不能假定完整 TypeScript 兼容"],
            ["UI", "声明式状态驱动、组件拆分", "ArkUI 状态装饰与组件生命周期遵循 HarmonyOS 契约"],
            ["生命周期", "前后台、恢复、资源释放的工程思维", "Stage/UIAbility 回调与 Android Activity 不是一一映射"],
            ["并发", "主线程预算、取消、结果过期控制", "并发模型与数据传递遵循 HarmonyOS API"],
            ["工程", "模块边界、制品、CI、测试分层", "HVigor 与 HAP/HAR 不等同 Gradle/AGP 与 AAR/APK"],
        ],
        "simulation": [
            ["0-5 分钟", "自我介绍：用 Android 经验说明可迁移能力与当前补齐计划，不夸大鸿蒙实战。"],
            ["5-18 分钟", "ArkTS、ArkUI 状态和组件生命周期：3 题 + 1 个状态错乱场景。"],
            ["18-30 分钟", "Stage/UIAbility、Want、权限：解释边界并设计异常路径。"],
            ["30-42 分钟", "并发、网络、存储：完成“旧请求覆盖新状态”白板题。"],
            ["42-52 分钟", "性能、调试、测试：给出证据链与复测方法。"],
            ["52-60 分钟", "分布式、工程化、Android 迁移：说明未知 API 如何查证并收尾提问。"],
        ],
    }


def render_harmonyos_interview(output: Path) -> None:
    data = harmonyos_interview_data()
    manifest = {
        "retrieved_on": HARMONYOS_RETRIEVED_ON, "copyright_policy": "No third-party article body or question list is stored. Community material only informs original topic taxonomy and follow-up directions.",
        "official_sources": [{"title": "HarmonyOS 官方文档：" + topic, "url": url, "access": "document landing page returned by public web endpoint"} for topic, url in HARMONYOS_SOURCES.items()],
        "open_source_repositories": [
            {"name": name, "url": url, "license": license_name, "observed_on": HARMONYOS_RETRIEVED_ON,
             "access": "GitHub public metadata: 200", "branch": "master"}
            for name, _, url, license_name, _, _, _ in HARMONYOS_OPEN_SOURCE_PROJECTS
        ],
        "research_failures": [{"url": "https://api.github.com/repos/openharmony/developtools_hvigor", "observed_on": HARMONYOS_RETRIEVED_ON, "error": "404 Not Found; omitted from project catalogue"}],
        "community_sources": [
            {"title": "掘金 HarmonyOS 标签页", "url": "https://juejin.cn/tag/HarmonyOS", "observed_on": HARMONYOS_RETRIEVED_ON, "access": "200", "use": "题型信号；未存储正文"},
            {"title": "Stack Overflow harmonyos 标签页", "url": "https://stackoverflow.com/questions/tagged/harmonyos", "observed_on": HARMONYOS_RETRIEVED_ON, "access": "403", "use": "访问受限；未用于技术事实"},
            {"title": "知乎 HarmonyOS 话题页", "url": "https://www.zhihu.com/topic/20114687/hot", "observed_on": HARMONYOS_RETRIEVED_ON, "access": "403", "use": "访问受限；未用于技术事实"},
        ],
    }
    atomic_write(output / "harmonyos-interview-zh.json", json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    atomic_write(output / "harmonyos-research-manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    topics = sorted({question["topic"] for question in data["questions"]})
    question_cards = "".join(
        """<article class="searchable question" data-kind="%s" data-topic="%s"><p class="meta">%s · %s · %s</p><h3>%s</h3><p><strong>标准口述答案：</strong>%s</p><p><strong>深入说明：</strong>%s</p><p><strong>常见误区：</strong>%s</p><p><strong>追问及参考回答：</strong>%s</p><p class="meta">来源依据（事实）：%s</p></article>""" % (
            html.escape(question.get("type", "基础题"), quote=True), html.escape(question["topic"], quote=True), html.escape(question["topic"]), html.escape(question["difficulty"]),
            html.escape(" / ".join(question["tags"])), html.escape(question["question"]), html.escape(question["standard_answer"]),
            html.escape(question["deep_dive"]), html.escape(question["pitfall"]), html.escape(question["follow_up"]),
            "".join('<a href="%s">%s</a> ' % (html.escape(item["url"], quote=True), html.escape(item["title"])) for item in question["official_sources"]))
        for question in data["questions"])
    scenario_cards = "".join("<article class=\"searchable\" data-kind=\"场景题\" data-topic=\"%s\"><h3>%s</h3><p><strong>回答框架：</strong>%s</p><p><strong>追问：</strong>%s</p><p class=\"meta\">来源依据（事实）：<a href=\"%s\">%s</a></p></article>" % (
        html.escape(item["topic"], quote=True), html.escape(item["question"]), html.escape(item["answer_framework"]), html.escape(item["follow_up"]),
        html.escape(item["official_sources"][0]["url"], quote=True), html.escape(item["official_sources"][0]["title"])) for item in data["scenarios"])
    compare = "".join("<tr><td>%s</td><td>%s</td><td>%s</td></tr>" % tuple(html.escape(value) for value in row) for row in data["android_quick_compare"])
    simulation = "".join("<li><strong>%s：</strong>%s</li>" % (html.escape(timebox), html.escape(content)) for timebox, content in data["simulation"])
    source_sections = "".join("<li><a href=\"%s\">%s</a>（检索日期：%s）</li>" % (html.escape(url, quote=True), html.escape(topic), HARMONYOS_RETRIEVED_ON) for topic, url in HARMONYOS_SOURCES.items())
    project_cards = "".join(
        "<article><h3><a href=\"%s\">%s</a></h3><p class=\"meta\">专题：%s · 许可证：%s · 访问日期：%s</p><p><strong>解决什么问题：</strong>%s</p><p><strong>项目主页/源码入口：</strong><a href=\"%s\">%s</a>；建议从 %s 开始。</p><p><strong>相关题目入口：</strong><a href=\"#questions\">在题库筛选“开源源码题”与“%s”</a>。</p><p><strong>二次开发练习：</strong>%s</p><p><strong>风险/版本提醒：</strong>公开仓库 master 分支与目标 SDK 可能不同；许可证来自公开 GitHub metadata，使用前仍需复核仓库 LICENSE。</p></article>" % (
            html.escape(item["url"], quote=True), html.escape(item["name"]), html.escape(item["topic"]), html.escape(item["license"]),
            HARMONYOS_RETRIEVED_ON, html.escape(item["summary"]), html.escape(item["url"], quote=True), html.escape(item["name"]),
            html.escape(item["entry"]), html.escape(item["topic"]), html.escape(item["practice"]))
        for item in data["open_source_projects"])
    body = """<nav aria-label="页面目录"><strong>目录：</strong><a href="#overview">概览</a> · <a href="#questions">题库</a> · <a href="#scenarios">场景题</a> · <a href="#opensource">开源项目</a> · <a href="#android-compare">Android 对照</a> · <a href="#mock-interview">模拟面试</a> · <a href="#sources">资料来源</a></nav>
<section id="overview"><h2>概览</h2><p class="meta"><strong>资料边界：</strong>%s</p><p>共 %d 道原创真题风格题、%d 道原创场景题和 %d 个开源项目。搜索与题型/专题筛选只作用于下方的题库和场景题；项目卡通过专题链接关联源码题。所有结论均需在目标 SDK/API 版本与目标设备上再次核验。</p></section>
<input id="search" placeholder="搜索题库或场景题（不搜索项目卡）"><select id="kind"><option value="">全部题型</option><option>基础题</option><option>场景题</option><option>开源源码题</option></select><select id="topic"><option value="">全部专题</option>%s</select>
<section id="questions"><h2>题库：真题风格题目</h2>%s</section>
<section id="scenarios"><h2>场景题：故障定位、架构取舍、性能与迁移</h2>%s</section>
<section id="opensource"><h2>开源项目：源码阅读与实践</h2><p class="meta">项目描述为原创归纳；项目主页和源码入口均指向公开项目主页。选择项目后请先固定目标分支或提交再形成技术结论。</p>%s</section>
<section id="android-compare"><h2>Android 开发者快速对照</h2><table><tr><th>领域</th><th>可迁移经验</th><th>必须重新验证</th></tr>%s</table></section>
<section id="mock-interview"><h2>60 分钟模拟面试</h2><ol>%s</ol></section>
<section id="sources"><h2>资料来源与研究状态</h2><h3>官方事实依据</h3><ul>%s</ul><p class="meta">社区题型调研与访问结果见 <a href="harmonyos-research-manifest.json">research manifest</a>；社区页面不作为 API 事实依据，也未被复制进题库。该 manifest 同时记录项目元数据访问、失败与访问限制。</p></section>
<script>const s=document.getElementById('search'),t=document.getElementById('topic'),k=document.getElementById('kind');function f(){for(const e of document.querySelectorAll('.searchable'))e.hidden=!(e.textContent.toLowerCase().includes(s.value.toLowerCase())&&(!t.value||e.dataset.topic===t.value)&&(!k.value||e.dataset.kind===k.value));}s.oninput=f;t.onchange=f;k.onchange=f;</script>""" % (
        html.escape(data["disclaimer"]), len(data["questions"]), len(data["scenarios"]), len(data["open_source_projects"]),
        "".join('<option>%s</option>' % html.escape(topic) for topic in topics), question_cards, scenario_cards, project_cards, compare, simulation, source_sections)
    atomic_write(output / "harmonyos-interview-zh.html", page("HarmonyOS / 鸿蒙中高级面试题库", body))
    atomic_write(output / "harmonyos-opensource-zh.html", """<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="0; url=harmonyos-interview-zh.html#opensource"><title>已合并到鸿蒙面试题库</title><p>开源项目内容已合并到 <a href="harmonyos-interview-zh.html#opensource">HarmonyOS / 鸿蒙中高级面试题库的“开源项目”章节</a>。</p>""")
    atomic_write(output / "harmonyos-opensource.json", json.dumps(data["open_source_projects"], ensure_ascii=False, indent=2) + "\n")


def render_site(issues: Iterable[Issue], output: Path) -> None:
    ordered = sorted(issues, key=lambda issue: issue.number, reverse=True)
    issue_dir = output / "issues"
    links = ['<p><a href="interview-case-library-zh.html">Jugg 面试项目案例：Flutter/C++ 外部源码增量构建（真实代码依据）</a></p>',
             '<p><a href="interview-template-zh.html">中文完整面试项目案例：XML 到 Compose 生产迁移（2/5 分钟版）</a></p>',
             '<p><a href="opensource-projects-zh.html">Android Weekly 开源项目情报库（本地识别与研究建议）</a></p>',
             '<p><a href="harmonyos-interview-zh.html">HarmonyOS / 鸿蒙中高级面试题库（题库、场景题、开源项目与资料来源）</a></p>']
    top_entries = sorted((entry for issue in ordered for entry in issue.entries), key=lambda entry: (-entry.score, entry.title.lower()))[:20]
    for issue in ordered:
        issue_page = "".join(entry_html(entry) for entry in issue.highlights)
        issue_page += "<p><a href=\"%s\">Open the original Android Weekly issue</a></p>" % html.escape(issue.url, quote=True)
        atomic_write(issue_dir / ("issue-%d.html" % issue.number), page(issue.title, '<p class="meta">%s</p><h2>Highlights</h2>%s' % (html.escape(issue.published), issue_page)))
        links.append('<article><h2><a href="issues/issue-%d.html">%s</a></h2><p class="meta">%s · %d links · %d highlights</p></article>' % (
            issue.number, html.escape(issue.title), html.escape(issue.published), len(issue.entries), len(issue.highlights)))
    top_html = "<h2>高分文章</h2><ol>" + "".join(
        '<li><a href="%s">%s</a> — %d/10</li>' % (html.escape(entry.url, quote=True), html.escape(entry.title), entry.score)
        for entry in top_entries) + "</ol>"
    render_interview_page(ordered, output)
    render_case_library(ordered, output)
    render_open_source_intelligence(ordered, output)
    render_harmonyos_interview(output)
    atomic_write(output / "index.html", page("Android Weekly highlights", "<p>Locally generated highlights with links to original sources.</p>" + top_html + "".join(links)))


def write_failures(root: Path, failures: list[dict[str, str]]) -> None:
    atomic_write(root / "failures.json", json.dumps(failures, ensure_ascii=False, indent=2) + "\n")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/android-weekly-data"), help="local data and site directory")
    parser.add_argument("--from-issue", type=int, dest="first", help="first issue number, inclusive")
    parser.add_argument("--to-issue", type=int, dest="last", help="last issue number, inclusive")
    parser.add_argument("--refresh", action="store_true", help="re-download cached pages")
    parser.add_argument("--rebuild-analysis", action="store_true", help="reparse cached raw HTML and refresh scores without downloading")
    parser.add_argument("--delay", type=float, default=1.0, help="minimum retry/request delay in seconds")
    parser.add_argument("--timeout", type=float, default=20.0, help="HTTP timeout in seconds")
    parser.add_argument("--retries", type=int, default=1, help="transient request retry count")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    if args.delay < 1 or args.timeout <= 0 or args.retries < 0:
        print("error: delay must be at least one second; timeout/retries must be non-negative", file=sys.stderr)
        return 2
    try:
        if args.rebuild_analysis:
            cached_numbers = sorted(
                int(path.stem.split("-")[1])
                for path in (args.output / "raw").glob("issue-*.html")
                if path.stem.split("-")[-1].isdigit()
            )
            candidates = [(number, "https://androidweekly.net/issues/issue-%d" % number) for number in cached_numbers]
            if not candidates:
                raise CrawlError("no cached raw issues found for analysis rebuild")
        else:
            archive = request_text(ARCHIVE_URL, args.delay, args.timeout, args.retries)
            candidates = discover_issues(archive)
        if args.first is not None or args.last is not None:
            low = args.first if args.first is not None else candidates[0][0]
            high = args.last if args.last is not None else candidates[-1][0]
            discovered = {number: url for number, url in candidates}
            candidates = [
                (number, discovered.get(number, "https://androidweekly.net/issues/issue-%d" % number))
                for number in range(low, high + 1)
            ]
        issues = []
        failures: list[dict[str, str]] = []
        for index, (number, url) in enumerate(candidates):
            if index and not args.rebuild_analysis:
                time.sleep(args.delay)
            try:
                issues.append(load_or_fetch_issue(number, url, args.output, args.refresh, args.rebuild_analysis, args.delay, args.timeout, args.retries))
            except CrawlError as error:
                failures.append({"number": str(number), "url": url, "error": str(error)})
                print("warning: %s" % error, file=sys.stderr)
        write_failures(args.output, failures)
        if not issues:
            raise CrawlError("no archive issues could be fetched and parsed")
        render_site(issues, args.output / "site")
    except CrawlError as error:
        print("error: %s" % error, file=sys.stderr)
        return 1
    print("stored %d/%d issues, %d failures, and rendered %s" % (
        len(issues), len(candidates), len(failures), args.output / "site" / "index.html"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
