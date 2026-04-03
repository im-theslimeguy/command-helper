const isPagesBuild = process.env.BUILD_TARGET === "pages";
const repoName = "command-helper";
const siteRoot = isPagesBuild ? `/${repoName}/` : "/";
const homeLink = isPagesBuild ? "../" : "/";

export default {
  lang: "en-US",
  title: "Command Helper Wiki",
  description: "Documentation for Command Helper.",
  base: `${siteRoot}wiki/`,
  ...(isPagesBuild ? { outDir: "../.pages/wiki" } : {}),
  themeConfig: {
    nav: [
      { text: "Home", link: homeLink },
      { text: "Wiki", link: "/" }
    ],
    sidebar: [
      {
        text: "Getting Started",
        items: [
          { text: "How to Get Your Gemini API Key", link: "/" }
        ]
      }
    ],
    socialLinks: [
      { icon: "github", link: "https://github.com/im-theslimeguy/command-helper" }
    ]
  }
};
