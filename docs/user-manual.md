# LedgerX user manual

This manual describes the current Android app, version **0.2.0**. LedgerX records your money, expenses, and personal loans in Bangladeshi taka (BDT, shown as **৳**). You can use it offline without signing in.

[Back to the project README](../README.md)

## Contents

- [Install and open the app](#install-and-open-the-app)
- [Set up your opening funds](#set-up-your-opening-funds)
- [Find your way around](#find-your-way-around)
- [Add a record](#add-a-record)
- [Record income](#record-income)
- [Record an expense](#record-an-expense)
- [Transfer between your accounts](#transfer-between-your-accounts)
- [Record money you lend](#record-money-you-lend)
- [Record money you borrow](#record-money-you-borrow)
- [Receive a repayment](#receive-a-repayment)
- [Pay back money you owe](#pay-back-money-you-owe)
- [Understand people and loan balances](#understand-people-and-loan-balances)
- [Worked example](#worked-example)
- [Read reports and history](#read-reports-and-history)
- [Troubleshooting](#troubleshooting)
- [Use your cloud account](#use-your-cloud-account)
- [Protect your records](#protect-your-records)

## Install and open the app

You need a phone or emulator running **Android 8.0 or newer**.

1. Obtain the debug APK built from this repository. The [README build instructions](../README.md#build-and-run) explain how to generate it.
2. Transfer the APK to your Android phone and open it. If Android requests permission to install from that source, allow it for the file manager or browser you are using, then complete installation.
3. Open **LedgerX** from your app list. If a developer installs it through Android Studio, open the installed app in the same way.
4. Wait for the ledger to load. You will see **Overview** and the **Add record** button.

Offline use has no registration step. Cloud accounts are optional, and there is no bank-linking step. A new installation starts with three zero-balance accounts: **Cash**, **bKash**, and **Bank**.

LedgerX records payments you make outside the app. It cannot check your real bank or bKash balance, send money, or request payment from another person.

## Set up your opening funds

Before recording spending or lending, enter the money you already have.

1. Tap **Add record**.
2. Open **Type** and select **Income**. The form initially defaults to Expense, so change it for this step.
3. Choose **Cash** under **Account**.
4. Enter your current cash amount in **Amount (BDT)**, for example `10000`.
5. Optionally enter `Opening cash balance` in **Note (optional)**.
6. Tap **Save record**. The form closes after a successful save.
7. Repeat for your bKash and Bank funds, selecting the correct account each time. Skip any account whose balance is zero.

For example, opening amounts of Cash ৳10,000, bKash ৳3,000, and Bank ৳20,000 produce **Available money: ৳33,000**.

Opening funds are ordinary Income records and count toward this month's income. There is no separate starting-balance field or existing-debt import. A Loan given or Loan taken always changes an account, so entering an old loan on top of today's opening balances can misstate your funds. The current app has no dedicated workflow to reconstruct historical balances.

## Find your way around

Use the bottom navigation to change screens. **Add record** is available from every screen after the ledger loads.

| Screen | What you can see |
| --- | --- |
| **Overview** | Available money, net worth, all three account balances, **Owed to you**, **You owe**, this month's expense breakdown, and up to five recent records. |
| **Money** | Account balances, this month's category spending, and **Income & spending · this month** totals. |
| **Loans** | Total amounts owed in both directions and an alphabetical list of people with their net balances and status. |
| **History** | All records, newest first, with the total number of entries. |

## Add a record

1. Tap **Add record** to open **Add a record**.
2. Select a **Type** and **Account**.
3. Complete the additional fields shown for that type.
4. Enter **Amount (BDT)** and, optionally, a note.
5. Check the type, amount, account, and person carefully, then tap **Save record**.

The button shows **Saving…** while the entry is being saved. A successful save closes the form and updates the screens. If validation fails, the form stays open with an error; correct the fields and save again. **Cancel** closes an unsaved form without adding a record.

### Field rules

| Field | How to enter it |
| --- | --- |
| **Type** | Choose one of the seven supported record types. |
| **Account** | The account money enters or leaves. For Transfer, this is the source. |
| **To account** | Shown for Transfer; choose a different destination account. |
| **Category** | Shown for Expense; choose Eat, Payment, or Other. |
| **Amount (BDT)** | Use digits `0–9` and optionally a decimal point: `250`, `250.5`, or `250.50`. Enter a positive amount, without commas or a currency symbol. Maximum: `1000000000`. |
| **Person's name** | Required for loans and repayments, up to 80 characters after normalization. Use **Choose existing person** when available. |
| **Note (optional)** | A description up to 300 characters after trimming, such as `Lunch` or `First repayment`. |

Dates and times are assigned when you create the record. You cannot choose a different date. Saved records cannot currently be edited, deleted, or undone.

## Record income

Use **Income** for money received that is not a loan, repayment, or transfer from another one of your accounts.

1. Tap **Add record** and select **Income**.
2. Choose the account that received the money.
3. Enter the amount and an optional note, such as `Salary`.
4. Tap **Save record**.

Example: Income of ৳5,000 into Bank increases Bank and Available money by ৳5,000. It also increases this month's Income total and your recorded net worth.

## Record an expense

Use **Expense** for spending such as food, bills, or purchases.

1. Tap **Add record** and select **Expense**.
2. Choose the account you paid from.
3. Select a category: **Eat** for food, **Payment** for bills or general payments, or **Other** for remaining spending. These are fixed labels; use them consistently for your own records.
4. Enter the amount and an optional note.
5. Tap **Save record**.

Example: Spending ৳250 from Cash under Eat reduces Cash, Available money, and net worth by ৳250. This month's Eat and total Expenses increase by ৳250.

The selected account must have enough recorded funds. A loan repayment should use **Repayment paid**, since an Expense under Payment does not reduce a person's loan balance.

## Transfer between your accounts

Use **Transfer** when moving your own money, such as depositing cash into your bank account.

1. Tap **Add record** and select **Transfer**.
2. Set **Account** to the account money leaves.
3. Set **To account** to the account money enters.
4. Enter the amount and an optional note.
5. Tap **Save record**.

Example: Transferring ৳2,000 from Cash to Bank reduces Cash by ৳2,000 and increases Bank by ৳2,000. Available money, net worth, income, and expense totals stay the same.

The two accounts must differ, and the source account must have enough funds. If the real transfer includes a fee, record that fee separately as an Expense from the account that paid it; there is no fee field in the transfer form.

## Record money you lend

Use **Loan given** when you give someone money they are expected to return.

1. Tap **Add record** and select **Loan given**.
2. Choose the account the money came from.
3. Enter the amount.
4. Enter **Person's name**, or tap **Choose existing person** and select them.
5. Optionally add a note, then tap **Save record**.

Example: Lending Rahim ৳1,500 from Cash reduces Cash by ৳1,500 and adds ৳1,500 to Rahim's signed loan balance. If you had no earlier balance with Rahim, Loans shows **Owes you** with a balance of **৳1,500**.

Lending reduces Available money but does not count as an expense. Net worth is unchanged because the amount is now owed to you. You need enough money in the selected account.

## Record money you borrow

Use **Loan taken** when someone gives you money you are expected to return.

1. Tap **Add record** and select **Loan taken**.
2. Choose the account that received the money.
3. Enter the amount and the person's name, using the existing-person list when appropriate.
4. Optionally add a note, then tap **Save record**.

Example: Borrowing ৳4,000 from Karim into bKash increases bKash by ৳4,000. With no earlier balance for Karim, Loans shows **You owe** and a negative balance of ৳4,000.

Borrowing increases Available money without increasing Income or net worth, because you also owe the money back.

## Receive a repayment

Use **Repayment received** when someone returns money they owe you.

1. Check **Loans** to see how much the person currently owes you.
2. Tap **Add record** and select **Repayment received**.
3. Choose the account that received the repayment. It can differ from the account you originally lent from.
4. Enter the amount received.
5. Tap **Choose existing person** and select the person.
6. Optionally add a note, then tap **Save record**.

Example: Rahim owes you ৳1,500 and returns ৳500 to Cash. Cash increases by ৳500 and Rahim now owes ৳1,000. Monthly Income stays unchanged.

The amount must not exceed what that person currently owes you. Receiving the full outstanding amount makes their balance zero and their status **Settled**.

## Pay back money you owe

Use **Repayment paid** when you return borrowed money.

1. Check **Loans** to see how much you currently owe the person.
2. Tap **Add record** and select **Repayment paid**.
3. Choose the account you paid from.
4. Enter the repayment amount and choose the existing person.
5. Optionally add a note, then tap **Save record**.

Example: You owe Karim ৳4,000 and pay back ৳1,000 from Bank. Bank decreases by ৳1,000 and you now owe Karim ৳3,000. Monthly Expenses stay unchanged.

The repayment cannot exceed the outstanding amount, and the selected account must have enough funds. Full repayment changes the person's status to **Settled**.

## Understand people and loan balances

People are created from loan and repayment entries; there is no separate contact-creation screen. These names are private labels, not phone contacts, registered users, or shared accounts. Signing into a cloud account does not connect the named people to your ledger.

- **Positive balance / Owes you:** the person owes you money.
- **Negative balance / You owe:** you owe the person money.
- **Zero / Settled:** neither side has a remaining net balance. The person remains listed.

Names are trimmed, repeated spaces are collapsed, and matching ignores letter case. `Rahim`, `rahim`, and ` Rahim ` reuse the same existing name. Different spellings can create separate people, so use **Choose existing person** for repeat entries. Give different people with the same name distinct labels, such as `Rahim Office` and `Rahim Cousin`.

Each person has one net balance across all accounts and records. If you lend Rahim ৳1,000 and later borrow ৳1,400 from him, the balance becomes negative by ৳400, meaning you owe him ৳400. Repayments can only settle that remaining net amount; the app does not maintain separately repayable loan agreements.

## Worked example

The following example starts from an empty ledger. All amounts are BDT, and all records are assumed to be created in the same month. Use your own real amounts when maintaining your actual ledger, because demonstration entries cannot be deleted.

| Step | Record to add | Available money afterward |
| --- | --- | ---: |
| 1 | Income: Cash, `10000`, note `Opening cash balance` | ৳10,000 |
| 2 | Income: bKash, `3000`, note `Opening bKash balance` | ৳13,000 |
| 3 | Income: Bank, `20000`, note `Opening bank balance` | ৳33,000 |
| 4 | Expense: Cash, Eat, `250` | ৳32,750 |
| 5 | Transfer: Cash → Bank, `2000` | ৳32,750 |
| 6 | Loan given: Cash, Rahim, `1500` | ৳31,250 |
| 7 | Loan taken: bKash, Karim, `4000` | ৳35,250 |
| 8 | Repayment received: Cash, Rahim, `500` | ৳35,750 |
| 9 | Repayment paid: Bank, Karim, `1000` | ৳34,750 |

After these entries, the screens should show:

| Item | Expected result |
| --- | ---: |
| Cash | ৳6,750.00 |
| bKash | ৳7,000.00 |
| Bank | ৳21,000.00 |
| Available money | ৳34,750.00 |
| Owed to you | ৳1,000.00 from Rahim |
| You owe | ৳3,000.00 to Karim |
| Net worth | ৳32,750.00 |
| This month's Income | ৳33,000.00, including opening funds |
| This month's Expenses / Eat | ৳250.00 |
| History count | 9 entries |

Net worth is `34750 + 1000 - 3000 = 32750`. Only the expense reduced it from the initial ৳33,000; transfers, loans, and repayments changed where the value was held or owed.

## Read reports and history

**Overview** gives a quick summary. Available money is the total of Cash, bKash, and Bank. Net worth adds what people owe you and subtracts what you owe them. Borrowing can therefore increase available money without increasing net worth.

**Money** shows the current month's Income and Expenses. Its category bars show each category's share of that month's expenses; they are not budgets or limits. Only Expense entries appear in the expense breakdown.

**Loans** shows all-time net balances per person. Owed to you sums positive balances; You owe sums the amounts of negative balances.

**History** lists all saved entries, newest first. Each card includes the record type and account, amount, date/time, and any note. Transfers show the source and destination. Income, loans taken, and repayments received have a plus sign; expenses, loans given, and repayments paid have a minus sign. Transfers show no plus/minus prefix because both accounts are involved. These signs describe account movement, not profit or debt status.

Reports use the device's current calendar month and timezone. Account balances, Loans, and History include older records too. There is no historical-month selector, history search, or filter.

## Troubleshooting

| Message or situation | What to do |
| --- | --- |
| **Enter an amount with up to two decimal places.** | Use a value such as `250.50`. Remove commas, currency symbols, negative signs, or extra decimal places. Use digits `0–9`. |
| **Amount must be greater than zero.** | Enter a positive amount. Skip an opening account balance of zero. |
| **Maximum entry is ৳1,000,000,000.** / **Amount is too large.** | Check for extra digits and stay within the per-record limit. |
| **Insufficient Cash/bKash/Bank balance. Add income first.** | Check the selected account and its recorded funds. Add missing real income/opening funds or record a real transfer where appropriate. A balance in another account does not automatically cover this one. |
| **Choose a different destination account.** | Select a To account different from Account. |
| **Enter a person's name (up to 80 characters).** | Enter a nonblank name within the limit, or choose an existing person. |
| **Repayment cannot exceed what this person owes you.** | Check Loans, select the correct person, and use Repayment received only for a positive outstanding balance. Enter no more than that balance. |
| **Repayment cannot exceed what you owe this person.** | Check Loans, select the correct person, and use Repayment paid only when you owe them. Enter no more than the outstanding amount. |
| **Note must be at most 300 characters.** | Shorten the note, then save again. |
| **Could not read your ledger. Please reopen the app.** | Close and reopen the app. Avoid clearing app storage or uninstalling, since those remove records. |
| **Could not save…** | Review History to confirm whether the entry appears before retrying. If the problem persists, close and reopen the app. |
| A person appears twice | Compare the spelling. Case and extra spaces are normalized, but spelling differences are separate names. There is no merge or rename feature. |
| Monthly income seems too high | Opening funds entered as Income are included. Loans taken and repayments received are excluded. |
| You saved an incorrect record | There is no edit, delete, undo, or supported correction workflow. Check History and keep a separate note of the discrepancy; adding an arbitrary opposite entry can distort reports. |
| Cloud reports missing tables or function | The project owner needs to complete the [Supabase setup](supabase-setup.md), then retry Sync. |
| Sign-in fails | Check your email/password and confirm your email if required. |
| Sync conflicts with cloud balances | Another device may have spent or repaid the same funds. The server rejects the upload and local records remain. There is no conflict-resolution editor yet. |
| No export or wallet option | File export and wallets are not available. Open Cloud for manual synchronization. |

## Use your cloud account

Cloud features require the project owner to complete the [Supabase setup](supabase-setup.md).

1. Tap **Cloud** in the top bar.
2. Enter your email and password. Use **Create account** for a new account, confirm the email if required, then **Sign in**.
3. Signing in opens a separate ledger for that account. Tap **Sync** to load records previously saved in the cloud.
4. To copy your original offline records into this account, check the displayed email and tap **Upload offline records**. This also performs a sync; the original offline ledger stays on this device. Repeating the upload does not duplicate matching records.
5. Add records as usual. They save to the account's local ledger first. Tap **Cloud → Sync** when online to upload changes and receive records from other devices.
6. Before moving to another device, sync successfully. Sign into the same account there and tap **Sync** to retrieve its records.
7. Tap **Sign out** to return to the original offline ledger.

Synchronization is manual. Merely signing in or saving a record does not upload it. Each device needs to tap Sync to receive new records. If both devices spend funds offline, their combined transactions may be rejected; local entries are kept, but this version has no conflict-resolution editor.

If a network request fails after reaching the server, retry Sync: records use unique IDs to prevent duplicate uploads. Cloud records stay private to your account and are not sent to the people named in loans.

## Protect your records

Saved records survive ordinary app restarts. **Uninstalling LedgerX or clearing storage removes local ledgers and your stored login session.** You can recover successfully synced records by signing in and syncing. Original offline records and unsynced entries remain device-only unless you upload them successfully.

There is no file export/import, and automatic Android backup/device transfer is excluded. Keep essential information separately before changing phones. LedgerX has no separate PIN/biometric lock or database encryption; use your device's access controls. Signing out leaves cached account records in app-private storage and returns to the original offline ledger.
