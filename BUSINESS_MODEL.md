# GURU Business Model (THE BIGGER PICTURE)

## The Core Premise

GURU is free. The app is free. Everything in it is free. No subscription. No paywall. No premium tier. No hidden costs. The user never pays Unus Lumen a penny to use the product.

Unus Lumen profits when its users prosper. We build the infrastructure. The users build the value. Everyone wins together or no one wins at all. Profit off prosperity, not poverty.

This is the opposite of the current tech industry model. Every other company profits by taking user data without consent, selling it behind the user's back, and charging the user for the privilege of being exploited. GURU flips that completely. The user owns their data. The user decides what happens to it. The user benefits when it's sold. Unus Lumen is the infrastructure that makes that possible.

## Revenue Stream 1: Spinner Ads

Traditionally, when an LLM is streaming a response, the user sees loading verbs or a traditional boring spinner animation. I thought this dead space that were forced to look at could be more intresting. so we replaced this space with qwerky animations & minigames instead. GURU replaces those boring loading states with small, quirky animated ads. Stickmen drinking Heineken or coke cola. caracters workign out at PureGym. stickman dancing to promote a viral song... This will nto be a traditional AD. This will be somethign you'd want to see. 

The adspace module is already built and functional. Ad packs are sent through the API, cached on-device, and rendered frame by frame during streaming. The system supports keyframes, poses, animation configs, text overlays, colour modes, and transitions. Any advert can be placed in this space.

This is a non-intrusive ad format. It doesn't block the user's view. It doesn't interrupt their workflow. It sits in the dead space that would otherwise show a generic loading animation. Advertisers get eyeballs. Users get entertained. Unus Lumen gets paid, THE APP STAYS FREE.

The ad content is delivered through the API, meaning it can be rotated, targeted, and updated without app updates. The AdSpaceManager handles display logic. AdPackCache handles local caching so ads work offline.

## Revenue Stream 2: Ottio

Ottio is the marketplace. It has two sides.

### Data Marketplace

GURU protects the user's device from other apps that want to farm their data. It stops the silent harvesting that every other app does. Instead, GURU collects data on behalf of the user, stores it securely encrypted on the user's device, and gives the user the option to sell it.

The user opts in. Always. No data is collected without consent. No data leaves the device without the user's explicit decision to sell.

When the user chooses to sell, they set the terms. A specific slice of data (search history from date X to date Y, browsing patterns, location data, whatever they're comfortable sharing) offered at a price they set. Vetted buyers send purchase requests to Unus Lumen. Unus Lumen sends those requests down into Ottio. The user sees the offer and decides whether to accept.

Unus Lumen takes a commission on every completed data transaction. The user gets the majority. Unus Lumen gets a small percentage for brokering the deal and maintaining the infrastructure.

This is a fundamental shift in who benefits from user data. Currently, tech companies take user data for free and sell it to advertisers for billions. The user sees none of that money. Ottio makes the user the seller, not the product. The user's data becomes the user's asset. The user monetises it on their own terms.

### Creator Marketplace

GURU gives users tools to build things. Toolkits. Skills. Masks. Agents. Games. Whatever they can imagine and create inside the framework.

Ottio lets them sell what they build. A user creates a sick new toolkit. They register it in Ottio. It gets tested and verified. It gets published. They set their own price. Other users buy it. The creator gets the majority. Unus Lumen takes a small commission on every sale.

The same applies to skills, masks, agents, and anything else users create. The creator economy runs itself. Unus Lumen keeps the lights on and takes a cut of every transaction for doing so.

This is an app store model but without the gatekeeping. Anyone can create. Anything can be sold. The community decides what's worth paying for through their purchases. Unus Lumen verifies and publishes but doesn't curate or restrict.

## Why This Works

The open-source model doesn't prevent monetisation. It enables it. GURU being AGPL-3.0 means anyone can use it, fork it, modify it. But Ottio only exists inside the GURU ecosystem. The marketplace is the walled garden that the open-source code leads into. The code is free. The economy is where the money lives.

The more users GURU gets, the more valuable Ottio becomes. More users means more data sellers, more data buyers, more creators, more buyers. The marketplace effect compounds. Each new user makes the marketplace more valuable for every other user.

The ad system scales with usage. More users streaming more responses means more ad impressions. The ad inventory grows with the user base. Advertisers pay more as the audience grows.

The commission model means Unus Lumen's revenue grows with the ecosystem's prosperity. We don't need to extract more from each user. We need more users transacting. Every transaction benefits us. Every transaction benefits the user. Aligned incentives.

## What's Built

The adspace module is fully functional. Stickman ad rendering, ad pack caching, animation configs, and the API delivery mechanism are all in the code today.

The Ottio marketplace has a placeholder screen in the navigation (OtioComingSoon) and a placeholder icon. The design exists. The backend and transaction system need to be built.

Device protection is partially implemented through the tool system. The privacy layer (Tor routing, on-device storage, no cloud) is built. The active blocking of other apps' data farming is a planned feature.

The Luxify skill framework is built. Skills can be created, stored, and executed. The marketplace layer for selling them is not yet built.

The mask system has a UI component (GuruMaskSelector) but is not fully implemented. Masks as a sellable product will come after the mask system itself is complete.

## What Needs Building

- Ottio marketplace backend: transaction processing, escrow, creator payouts, buyer verification
- Ottio marketplace UI: browsing, purchasing, listing, pricing, creator dashboards
- Data collection and curation pipeline: GURU collecting data on behalf of the user, stored securely on-device, ready for sale
- Buyer vetting pipeline: Unus Lumen reviews and approves data purchase requests before they reach users
- Ad network partnerships: commercial deals with advertisers to fill the spinner ad inventory
- Mask system completion: before masks can be sold, the mask system itself needs to work
- Creator verification: testing and verification pipeline for user-created content before it goes live in Ottio

## The Opportunity

Unus Lumen is building infrastructure for a user-owned economy. The revenue model is simple: take a small cut of every transaction in a marketplace that grows itself. The more users prosper, the more Unus Lumen earns. The incentives are aligned.

The ad system is ready today. Ottio is designed and partially scaffolded. The skill framework, the mask system, the tool system, and the device protection layer are all pieces of the ecosystem that feed into the marketplace. Each one gives users more to create with and more to sell.

This is a commercial co-founder opportunity. The product is built. The model is clear. What's needed is someone to drive the business side: ad network partnerships, buyer relationships for the data marketplace, creator outreach, and the commercial strategy for launching Ottio. The technical foundation is solid. The commercial layer needs someone to own it.

**GURU and its business model: founded, built and financed into existence by Steven Newman, Unus Lumen, Bristol UK.**