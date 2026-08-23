# Nirmalam Dhanam user guide

Nirmalam Dhanam is a private, local-first personal-finance app. Your money data is stored in an encrypted database on your device. The app is designed for a short daily check-in: record what happened, see what is safe to use, and keep a calm long-term view of your wealth.

The app uses Sanskrit-first labels written in English letters. English explanations are shown throughout the app; the glossary below is a quick reference.

## Concepts

### Prarambha — home

`Prarambha` is the daily starting point. It shows the cash available for everyday decisions, today’s safe-to-spend amount, any cooling-tank items, and a compact Nivesha summary.

### Vyavahara — money activity

`Vyavahara` is a single money event: an Aaya arriving, a Vyaya leaving, or an investment contribution. It has an amount, a Varga, an optional Vyakti, and an optional private description.

### Aaya and Vyaya — inflow and outflow

`Aaya` is money received, such as salary, a gift, or freelance income. `Vyaya` is money used, such as food, transport, bills, or shopping.

### Khata — money place

`Khata` means the place where money is held or owed: cash, a bank account, credit card, loan, or retirement/investment holding. The daily entry flow uses your cash or bank Khata; investment holdings use their own valuation check-ins.

### Varga — category

`Varga` describes the purpose of an Aaya or Vyaya. Categories are reusable and may have a visual glyph. A Varga is not a balance-holding Khata; it is a way to understand where money comes from or goes.

### Vyakti — payee or payer

`Vyakti` is a person, shop, employer, or institution involved in a Vyavahara. A saved Vyakti can have a default Varga, making repeat entry faster and more consistent.

### Nivesha — investments

`Nivesha` is the long-term investment area. Rather than asking you to record every market movement, it uses dated check-ins: enter the cumulative cost and current value from your statement or portfolio view.

### Sampada — overall wealth

`Sampada` is the overall wealth reading. It combines available cash, relevant reserves, and the latest Nivesha valuations. It is a useful direction-of-travel measure, not investment advice.

### Cooling tank

Larger wants purchases are held for 48 hours before confirmation. While an item is in the cooling tank it does not reduce the active liquid position. You can release it, or confirm it once the timer ends.

## Features

### 1. Unlock the local encrypted database

1. Open the app.
2. Enter a passphrase of at least eight characters.
3. Use the same passphrase on later launches to open the existing encrypted data.

Do not forget this passphrase. The app cannot recover it or read the encrypted database without it.

### 2. Set up Khatas

From `Prarambha`, select **Set up Khata or Nivesha**. Choose the type that matches what you are tracking:

- Cash or Bank for everyday Aaya and Vyaya.
- Credit card or Loan for liabilities.
- PPF, EPF, NPS, Superannuation, Mutual Funds, or Equity for Nivesha holdings.

For Nivesha Khatas, you may assign an asset class and a target allocation percentage. This supports allocation comparison in `Nivesha & Sampada`.

### 3. Record a Vyavahara

1. In `Prarambha`, choose **Vyaya · outflow** or **Aaya · inflow**.
2. Enter the amount in rupees.
3. Choose a Varga.
4. Optionally select or type a Vyakti.
5. Add a private description when it helps future you understand the entry.
6. Select **Add Vyaya** or **Add Aaya**.

For a new Vyakti, typing the name saves it for future use. Saved Vyaktis appear in the selector. Choosing one applies its saved default Varga when present.

### 4. Manage Varga and Vyakti

Open `Vinyasa` to manage your reusable lists.

#### Add a Varga

1. Under **Varga · categories**, choose **Add**.
2. Enter the name.
3. Choose whether it is for Vyaya or Aaya.
4. Choose a glyph.
5. Save.

User-created Varga entries can be removed later. Built-in starter Varga entries are protected so the basic entry experience stays usable.

#### Add a Vyakti

1. Under **Vyakti · payees**, choose **Add**.
2. Enter the person, shop, employer, or institution name.
3. Optionally enter its default Varga.
4. Save.

Removing a Vyakti only removes it from the reusable list. Existing Vyavahara records retain their historical text.

### 5. Review Vyavahara

Open `Vyavahara` from the bottom navigation.

- Tap the search glyph at the top-right to search by Vyakti, Varga, Khata, or description.
- Use **Khata scope** to see one Khata or all Khatas.
- Use the horizontal Varga list to focus on one category.
- Choose 7 days, this month, 3 months, 6 months, this year, or all activity.
- Filter to all activity, Vyaya, or Aaya.

The **Money Pulse** summarizes inflow, outflow, and net movement for the active filters. The **Spend Map** shows the leading Vyaya Varga entries and the most-used Vyakti. These insights are calculated only from the encrypted records already on the device.

### 6. Track Nivesha and Sampada

Open `Nivesha` from the bottom navigation, or use the shortcut in `Prarambha`.

#### Record a valuation check-in

1. Select **Check-in** or **Record monthly balance**.
2. Select the Nivesha Khata.
3. Enter the date, cumulative cost, current value, and optional contribution and note.
4. Save.

The app uses the latest check-in per Nivesha Khata for allocation and Sampada calculations. A valuation check-in is not an everyday spending entry.

#### Record an investment contribution

Use **Nivesha** / **Contribute from cash / bank** when money moves from a daily Khata into an investment. This records the outflow from cash or bank and updates the selected Nivesha cost and value on the current date.

### 7. Use neurodiverse mode

In `Vinyasa`, turn on neurodiverse mode for a calmer presentation with reduced information density and a stronger emphasis on the next essential action. It does not alter calculations, records, or protections.

## Frequently asked questions

### Is my financial data uploaded anywhere?

No. The app is designed to work locally. Its database is encrypted on the device. The app’s calculations and Spend Map use local records.

### What is the difference between a Khata and a Varga?

A Khata holds or owes money, such as cash, a bank balance, a credit card, or an investment. A Varga explains the purpose of a money event, such as Food, Salary, or Transport.

### Why is Nivesha tracked by balance check-ins instead of daily transactions?

Periodic cost-and-value check-ins reduce friction while producing a useful portfolio and Sampada history. Use an investment contribution when you want the transfer from cash or bank recorded as well.

### Why did my shopping entry go into the cooling tank?

Vyaya classified as a want and above the configured threshold enters a 48-hour cooling tank. It remains outside the active liquid position until you confirm it after the timer ends.

### Can I delete a Vyavahara?

Yes. Open `Vyavahara`, find the entry, and select **Delete**. The app asks for confirmation because the deletion updates associated balances and summaries.

### Can I create my own Varga and choose an icon?

Yes. Open `Vinyasa` → **Varga · categories** → **Add**. Choose the name, Aaya/Vyaya direction, and glyph. The new Varga appears in the entry form and the Ledger filters.

### Can a Vyakti be both someone I pay and someone who pays me?

Yes. A Vyakti is simply a reusable counterparty. You can use the same one for Aaya and Vyaya. If you prefer separate reporting names, create separate Vyakti entries.

### How do I back up my data?

The encrypted `.ndf` backup format and Android file-picker support are part of the data layer. The in-app backup controls are not yet exposed in `Vinyasa`; until they are, preserve your device and passphrase carefully.

### What should I do if I forget my passphrase?

The encrypted data cannot be recovered without the passphrase. This is intentional: it prevents the app, its developer, and other apps from reading your financial records.

