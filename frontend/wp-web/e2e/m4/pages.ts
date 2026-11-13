import { expect, type Download, type Locator, type Page } from "@playwright/test";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
export const WAREHOUSE_TTL = resolve(
  HERE,
  "../../../../testdata/ontologies/warehouse-sample.ttl",
);

export const PALLET_IRI = "http://example.org/wp-test/warehouse#Pallet";
export const HANDLING_UNIT_IRI =
  "http://example.org/wp-test/warehouse#HandlingUnit";
export const SUBCLASS_NAME = "周转托盘";
export const ENGLISH_LABEL = "Turnover pallet";
export const SEARCH_QUERY = "pallet";

export async function forceZhLocale(page: Page): Promise<void> {
  await page.addInitScript(() => {
    window.localStorage.setItem("wp.locale", "zh-CN");
  });
}

export class LoginPage {
  constructor(readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto("/login");
    await expect(this.page.getByRole("heading", { name: "登录" })).toBeVisible();
  }

  async login(username: string, password: string): Promise<void> {
    await this.goto();
    await this.page.getByLabel("用户名").fill(username);
    await this.page.getByLabel("密码", { exact: true }).fill(password);
    await this.page.getByRole("button", { name: "登录" }).click();
    await expect(this.page).not.toHaveURL(/\/login/);
  }
}

export class AdminUsersPage {
  constructor(readonly page: Page) {}

  async openFromUserMenu(): Promise<void> {
    await this.page.getByRole("button", { name: /用户菜单/ }).click();
    await this.page.getByRole("menuitem", { name: "用户管理" }).click();
    await expect(
      this.page.getByRole("heading", { name: "用户管理" }),
    ).toBeVisible();
  }

  async createUser(input: {
    userId: string;
    displayName: string;
    email: string;
    initialPassword: string;
  }): Promise<void> {
    await this.page.getByRole("button", { name: "新建用户" }).click();
    const dialog = this.page.getByRole("dialog", { name: "新建用户" });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("用户 ID").fill(input.userId);
    await dialog.getByLabel("显示名称").fill(input.displayName);
    await dialog.getByLabel("邮箱").fill(input.email);
    await dialog.getByLabel("初始密码").fill(input.initialPassword);
    await dialog.getByRole("button", { name: "创建" }).click();
    await expect(dialog).toBeHidden();
  }
}

export class ProjectListPage {
  constructor(readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto("/projects");
    await expect(this.page.getByRole("heading", { name: "项目" })).toBeVisible();
  }

  async uploadOntology(filePath: string, name: string): Promise<string> {
    await this.goto();
    await this.page.getByRole("button", { name: "上传导入" }).click();
    const dialog = this.page.getByRole("dialog", { name: "上传本体创建项目" });
    await expect(dialog).toBeVisible();
    await dialog.locator('input[type="file"]').setInputFiles(filePath);
    await dialog.getByLabel("名称").fill(name);
    await dialog.getByLabel("描述").fill("M4 acceptance");
    await dialog.getByRole("button", { name: "开始上传" }).click();
    await expect(dialog.getByText("上传进度")).toBeVisible({ timeout: 30_000 });
    // Upload succeeds, but Vue Router 5 resolves child redirect "class" to "/class"
    // (replacing the last path segment). Open via the project list + /class deep link.
    await expect(dialog).toBeHidden({ timeout: 120_000 });
    await this.goto();
    const link = this.page.getByRole("link", { name });
    await expect(link).toBeVisible({ timeout: 30_000 });
    const href = await link.getAttribute("href");
    const match = href?.match(/\/projects\/([^/?#]+)/);
    if (match === null || match === undefined || match[1] === undefined) {
      throw new Error(`expected project link href, got ${href ?? "null"}`);
    }
    const projectId = match[1];
    await this.page.goto(`/projects/${projectId}/class`);
    await expect(this.page).toHaveURL(new RegExp(`/projects/${projectId}/`));
    return projectId;
  }

  async expectProjectAbsent(name: string): Promise<void> {
    await this.goto();
    await this.page.getByRole("tab", { name: "我的项目" }).click();
    await expect(this.page.getByRole("link", { name })).toHaveCount(0);
  }

  async expectProjectInTrash(name: string): Promise<void> {
    await this.goto();
    await this.page.getByRole("tab", { name: "回收站" }).click();
    await expect(this.page.getByText(name)).toBeVisible();
  }

  async moveToTrash(name: string): Promise<void> {
    await this.goto();
    await this.page.getByRole("tab", { name: "我的项目" }).click();
    const row = this.page.locator("tr", {
      has: this.page.getByRole("link", { name }),
    });
    await row.getByRole("button", { name: "移入回收站" }).click();
    await this.page.getByRole("button", { name: "确定" }).click();
    await expect(this.page.getByRole("link", { name })).toHaveCount(0);
  }
}

export class SettingsPage {
  constructor(readonly page: Page) {}

  async openDisplayNames(projectId: string): Promise<void> {
    await this.page.goto(`/projects/${projectId}/settings?tab=display-names`);
    await expect(this.page.getByRole("tab", { name: "显示语言" })).toBeVisible();
  }

  async setDisplayNamesEmptyZhEn(): Promise<void> {
    while ((await this.page.locator(".display-name-settings__row").count()) > 0) {
      await this.page
        .getByRole("button", { name: /删除第 1 项|Remove row 1/i })
        .first()
        .click();
    }
    for (const lang of ["", "zh", "en"]) {
      await this.page.getByTestId("add-row").click();
      const rows = this.page.locator(".display-name-settings__row");
      const row = rows.nth((await rows.count()) - 1);
      await row.locator(".el-select").click();
      const option = this.page
        .locator(".el-select-dropdown:visible")
        .getByRole("option", { name: /^rdfs:label/ })
        .first();
      await expect(option).toBeVisible();
      await option.evaluate((el: HTMLElement) => {
        el.scrollIntoView({ block: "nearest" });
        el.click();
      });
      await expect(row.locator(".el-select")).toContainText("rdfs:label");
      await row.locator('input[name="lang"]').fill(lang);
    }
    const save = this.page.getByRole("button", { name: "保存", exact: true });
    await expect(save).toBeEnabled();
    await save.click();
    await expect(this.page.getByText(/已保存/)).toBeVisible({ timeout: 15_000 });
  }

  async openMembers(projectId: string): Promise<void> {
    await this.page.goto(`/projects/${projectId}/settings?tab=members`);
    await expect(this.page.getByRole("tab", { name: "成员" })).toBeVisible();
  }

  async addEditor(userId: string): Promise<void> {
    const section = this.page.locator("section.members-tab__add");
    await this.page.getByTestId("add-user-id").fill(userId);
    await section.locator(".el-select").first().click();
    await this.page
      .locator(".el-select-dropdown:visible")
      .getByRole("option", { name: /编辑 — 可以编辑本体内容/ })
      .click();
    await this.page.getByRole("button", { name: "添加成员" }).click();
    await expect(this.page.locator(`[data-user-id="${userId}"]`)).toBeVisible();
    await expect(this.page.getByTestId(`member-role-${userId}`)).toContainText(
      /编辑/,
    );
  }
}

export class AccountPage {
  constructor(readonly page: Page) {}

  async openFromUserMenu(): Promise<void> {
    await this.page.getByRole("button", { name: /用户菜单/ }).click();
    await this.page.getByRole("menuitem", { name: "个人设置" }).click();
    await expect(
      this.page.getByRole("heading", { name: "个人设置" }),
    ).toBeVisible();
  }

  async createApiKeyOnce(name: string): Promise<string> {
    await this.page.getByRole("tab", { name: "API Key" }).click();
    await this.page.getByRole("button", { name: "新建" }).click();
    const create = this.page.getByRole("dialog", { name: "新建 API Key" });
    await expect(create).toBeVisible();
    await create.getByLabel("名称").fill(name);
    await create.getByRole("button", { name: "创建" }).click();
    const issued = this.page.getByRole("dialog", { name: "API Key 已创建" });
    await expect(issued).toBeVisible();
    await expect(issued.getByText(/只显示这一次/)).toBeVisible();
    const plaintext = issued.getByLabel("API Key 已创建");
    const value = await plaintext.inputValue();
    expect(value.length).toBeGreaterThan(0);
    await issued.getByRole("button", { name: "关闭", exact: true }).click();
    await expect(issued).toBeHidden();
    return value;
  }
}

export class WorkspacePage {
  constructor(readonly page: Page) {}

  async openClassHierarchy(projectId: string): Promise<void> {
    await this.page.goto(`/projects/${projectId}/class`);
    await expect(this.page.getByTestId("hierarchy-tree")).toBeVisible({
      timeout: 60_000,
    });
  }

  tree(): Locator {
    return this.page.getByTestId("hierarchy-tree");
  }

  nodeByLabel(label: string, iriFragment?: string): Locator {
    const pattern =
      iriFragment === undefined
        ? new RegExp(`^${escapeRegExp(label)}(\\.| \\()`)
        : new RegExp(
            `^${escapeRegExp(label)}.*${escapeRegExp(iriFragment)}`,
          );
    return this.tree().getByLabel(pattern);
  }

  async expandNode(label: string, iriFragment?: string): Promise<void> {
    const node = this.nodeByLabel(label, iriFragment);
    await this.scrollNodeIntoView(node);
    await expect(node).toBeVisible({ timeout: 30_000 });
    const row = treeRow(node);
    const expand = row.locator(".el-tree-node__expand-icon").first();
    const isLeaf = await expand.evaluate((el) =>
      el.classList.contains("is-leaf"),
    );
    if (!isLeaf) {
      const expanded = await expand.evaluate((el) =>
        el.classList.contains("expanded"),
      );
      if (!expanded) {
        await expand.click();
      }
    }
  }

  async scrollNodeIntoView(node: Locator): Promise<void> {
    for (let attempt = 0; attempt < 12; attempt += 1) {
      if (await node.count() > 0 && (await node.isVisible().catch(() => false))) {
        await node.scrollIntoViewIfNeeded();
        return;
      }
      await this.tree().evaluate((el) => {
        const main =
          el.querySelector(".el-vl__wrapper") ??
          el.querySelector(".el-tree-v2__main") ??
          el;
        main.scrollTop += 160;
      });
    }
  }

  async expectLeaf(label: string, iri: string): Promise<void> {
    const expand = await this.expandIconForIri(iri, label);
    await expect(expand).toHaveClass(/is-leaf/);
  }

  async expectExpandableWithin(
    label: string,
    iri: string,
    timeoutMs: number,
  ): Promise<void> {
    const expand = await this.expandIconForIri(iri, label);
    await expect(expand).not.toHaveClass(/is-leaf/, { timeout: timeoutMs });
  }

  async expandIconForIri(iri: string, label: string): Promise<Locator> {
    const labelEl = this.tree().locator(
      `.hierarchy-tree__label[title="${iri}"]`,
      { hasText: label },
    );
    await this.scrollNodeIntoView(labelEl);
    await expect(labelEl).toBeVisible({ timeout: 30_000 });
    return labelEl.locator(
      'xpath=ancestor::*[contains(@class,"el-tree-node__content")][1]//*[contains(@class,"el-tree-node__expand-icon")]',
    );
  }

  async createSubclassViaContextMenu(
    parentLabel: string,
    parentIri: string,
    childName: string,
  ): Promise<void> {
    const parent = this.nodeByLabel(parentLabel, parentIri);
    await parent.click({ button: "right" });
    await this.page.getByRole("menuitem", { name: "新建子类" }).click();
    const dialog = this.page.getByRole("dialog", { name: "新建实体" });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("名称（每行一个）").fill(childName);
    await dialog.getByRole("button", { name: "创建" }).click();
    await expect(dialog).toBeHidden();
  }

  async openHistory(): Promise<void> {
    await this.page.getByRole("link", { name: "修订历史" }).click();
    await expect(
      this.page.getByRole("heading", { name: "修订历史" }),
    ).toBeVisible();
  }

  async searchAndSelect(query: string, resultName: string): Promise<void> {
    const search = this.page.getByRole("combobox", { name: "搜索实体" });
    await search.fill(query);
    const option = this.page
      .locator(".global-search__option")
      .filter({
        has: this.page.locator(".global-search__name", {
          hasText: new RegExp(`^${escapeRegExp(resultName)}$`),
        }),
      })
      .first();
    await expect(option).toBeVisible({ timeout: 30_000 });
    await option.click();
    await expect(this.page).toHaveURL(/[?&]entity=/);
    await expect(this.page.getByTestId("class-frame-editor")).toBeVisible({
      timeout: 30_000,
    });
  }

  async expectSearchMiss(query: string, resultName: string): Promise<void> {
    const search = this.page.getByRole("combobox", { name: "搜索实体" });
    await search.fill("");
    await search.fill(query);
    await expect(
      this.page.getByRole("option", { name: new RegExp(resultName) }),
    ).toHaveCount(0, { timeout: 15_000 });
  }

  async downloadTurtle(): Promise<Download> {
    const downloadPromise = this.page.waitForEvent("download");
    await this.page.getByRole("button", { name: "下载" }).click();
    await this.page.getByRole("menuitem", { name: "Turtle (.ttl)" }).click();
    return downloadPromise;
  }

  async expectTrashedMessage(): Promise<void> {
    await expect(this.page.getByRole("alert")).toContainText(/项目.*回收站/);
  }
}

export class ClassFrameEditor {
  constructor(readonly page: Page) {}

  root(): Locator {
    return this.page.getByTestId("class-frame-editor");
  }

  async openEntity(label: string, iriFragment?: string): Promise<void> {
    const workspace = new WorkspacePage(this.page);
    await workspace.nodeByLabel(label, iriFragment).click();
    await expect(this.root()).toBeVisible({ timeout: 30_000 });
  }

  async addEnglishLabel(text: string): Promise<void> {
    await this.root().getByTestId("add-label").click();
    const lexicals = this.root().getByTestId("annotation-lexical");
    const langs = this.root().getByTestId("annotation-lang");
    const count = await lexicals.count();
    const lexical = lexicals.nth(count - 1);
    const lang = langs.nth(count - 1);
    await lexical.fill(text);
    await lang.fill("en");
    await this.page.getByRole("button", { name: "保存", exact: true }).click();
    await this.expectLabelVisible(text);
  }

  async expectLabelVisible(text: string): Promise<void> {
    await expect
      .poll(
        async () => {
          const values = await this.root()
            .getByTestId("annotation-lexical")
            .evaluateAll((els) =>
              els.map(
                (el) => (el as HTMLInputElement | HTMLTextAreaElement).value,
              ),
            );
          return values.includes(text);
        },
        { timeout: 30_000 },
      )
      .toBe(true);
  }
}

export class HistoryPage {
  constructor(readonly page: Page) {}

  async expectLatestDescriptions(...descriptions: string[]): Promise<void> {
    for (const description of descriptions) {
      await expect(
        this.page.getByText(description, { exact: true }),
      ).toBeVisible();
    }
  }

  async revertLatest(): Promise<{ revertedRev: string }> {
    const firstRow = this.page.locator(".el-table__body tr").first();
    const revText = (await firstRow.locator("td").first().innerText()).trim();
    const revNo = revText.replace(/\D+/g, "");
    await firstRow.getByRole("button", { name: /回滚/ }).click();
    const dialog = this.page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await dialog.getByLabel(new RegExp(`请输入修订号 ${revNo}`)).fill(revNo);
    await dialog
      .getByRole("button", { name: new RegExp(`确认回滚修订 ${revNo}`) })
      .click();
    await expect(dialog).toBeHidden({ timeout: 30_000 });
    await expect(
      this.page.getByText(`Reverted revision ${revNo}`, { exact: true }),
    ).toBeVisible({ timeout: 30_000 });
    return { revertedRev: revNo };
  }
}

function treeRow(node: Locator): Locator {
  return node.locator(
    'xpath=ancestor::*[contains(@class,"el-tree-node") and not(contains(@class,"el-tree-node__children"))][1]',
  );
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
