export const site = {
  name: "Luma Home",
  publisher: "Zadhron Dynamic",
  url: process.env.NEXT_PUBLIC_SITE_URL || "https://lumahome.zadhron.com",
  companyUrl: "https://zadhron.com",
  supportEmail: process.env.NEXT_PUBLIC_SUPPORT_EMAIL || "",
};

export const contactHref = site.supportEmail
  ? `mailto:${site.supportEmail}`
  : site.companyUrl;
