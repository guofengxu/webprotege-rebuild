import { expect, test } from "@playwright/test";

test("an anonymous visit opens the login form", async ({ page }) => {
  await page.route("**/api/**", (route) => route.fulfill({ status: 404 }));
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/problem+json",
      body: JSON.stringify({
        title: "Unauthorized",
        status: 401,
        detail: "Authentication required",
      }),
    }),
  );

  await page.goto("/");

  await expect(page).toHaveURL(/\/login\?redirect=(%2F|\/)$/);
  await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();
  await expect(page.getByLabel("用户名")).toBeVisible();
  await expect(page.getByLabel("密码", { exact: true })).toBeVisible();
});
