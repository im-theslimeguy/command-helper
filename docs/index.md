# How to Get Your Gemini API Key

To use the **Ask Assistant** feature in Command Helper, you need a "Gemini API Key". Think of it as a special password that lets your game talk to Google's AI.

Don't worry if you've never done this before! Just follow these exact steps slowly, and you'll be done in 5 minutes.

## Getting Your Key

**Step 1: Go to the Google AI Studio website**  
Click this exact link to open the page: [https://aistudio.google.com/](https://aistudio.google.com/)

**Step 2: Sign in to Google**  
If you aren't already logged in, it will ask you to sign in with your regular Google account (the same one you use for YouTube or Gmail).

**Step 3: Accept the Terms (if asked)**  
If this is your first time here, a welcome screen might pop up. Check the boxes to agree to the terms and click the **Continue** button.

**Step 4: Click "Create API Key"**
![Step 4 screenshot](/1.png)

Look at the bottom-left corner of the screen. Above the settings button, which is above your profile picture, there should be a **Get API key** button. Click it!

**Step 5: Create a new project**  
![Step 5 screenshot A](/2.png)

In the top-right corner, click the **Create API key** button. A popup will appear where you need to give your API key a name, then choose a Cloud project for it. If you already have one, select it. If you don't have one yet, click the dropdown and then the **Create project** button.

![Step 5 screenshot B](/3.png)

Fill out the form. The system will automatically use the newly created project for your API key. After that, click the **Create key** button.

![Step 5 screenshot C](/4.png)

**Step 6: Copy your key**  
A new row should appear. At the start of the row, there will be blue text like `...XXXX`. Click on it. Those are the final 4 characters of your API key.

![Step 6 screenshot A](/5.png)

Then, a window will show your new API key. It is a very long text that starts with `AIza...`. Click the **Copy** button right next to it.

![Step 6 screenshot B](/6.png)

*(Warning: Treat this like a real password. Do not send this text to your friends or post it on the internet!)*

**Step 7: Paste it into Minecraft**  
Open Minecraft, go to the Command Helper mod, open the Ask Assistant window, and paste (`Ctrl+V`) this long text into the API key text box.

---

## Geolocational restrictions

**Are you living in the European Union, the UK, or Switzerland?** If yes, your API key will **not** work immediately. If you try to use it, the mod will show an `HTTP 429` error.

**Why does this happen?**  
Because of European privacy laws, Google does not allow free AI usage without verifying who you are. They require you to add a bank card to your account to prove you are a real person and to unlock the service.

**Will they take my money?**  
No! As long as you are only using this key for yourself in Minecraft, you will stay in the "Free Tier" limits. But the system *needs* the card attached, otherwise the limit is locked at zero.

### Here is exactly how to fix the error:

**Step 1: Go to the Google Cloud Console**  
Click this link: [https://console.cloud.google.com/billing](https://console.cloud.google.com/billing)

**Step 2: Create a Billing Account**  
Click on **Manage Billing Accounts** or **Link a Billing Account**. Then click **Create Account**.

**Step 3: Fill in your details**  
It will ask for your country, address, and your bank card details. Fill everything out honestly.

**Step 4: Link it to your project**  
Make sure this new billing account is connected to the **Generative Language Client** project (the project you created in Part 1).

**Step 5: Restart your game**  
Close Minecraft completely and open it again. Try the Ask Assistant button now. It should work perfectly!

> Pro tip: If you want to be 100% safe, you can look for the **Budgets & alerts** menu in the Google Cloud Billing page and set a budget of `$1.00`. This way, you will get an email if you ever accidentally use too much.
