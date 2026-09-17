# A Note From Steven Newman, Founder and Developer

**GURU is ready to download today! For FREE! Complete source and apk.**

Hello everyone.

The time has come to fully open source GURU by Unus Lumen.

There are a few things you WILL need to know before downloading.

GURU is NOT finished. It is NOT perfect. However, even in its current state, GURU is night and day different to anything you've ever used before.

Some of the tools have bugs, some are stubs and some of the permissions have not been wired in yet. THIS DOES NOT AFFECT THE FUNCTIONALIITUY... GURU can fix itself, And will... in real time. This is a design choice. these bugs and stubs will be rectified imminently, however nothing like this has been done yet. I'm working from a completely blank slate with no reference material, so in order to keep the app working as advertised, every little aspect of the build, from wiring a simple permission to engineering and building the toolkit and skills for it to work, has to be thought through and tested meticulously.

It is in good working order and it is very very usable. Actually, it is a very pleasant user experience. YOU DO NOT NEED PERMISSIONS FOR GURU TO WORK. GURU will work fine without permissions, however I designed and engineered this app for it to have these permissions in order to work properly.

One thing you do need to know. The skills, prompts, tools, agent definitions and packs get sent through the Unus Lumen API. We're not yet a model provider, we don't see your conversations and we never will. But in order for the app to work properly, you will need to keep the app connected to our API. This connection is RECEIVE ONLY. Nothing from your device goes up to us. The API only publishes content down to your GURU.

This is very immature, category defining technology, and that is exactly why I'm open sourcing it. Young, category defining tech should be community driven. Open source is the only road now. PRs are VERY welcome. All I've done is show that this level of capability, control and privacy on an ordinary phone is possible. Now I'm asking you lot to help me make it better, safer and more private.

By pulling, installing and using GURU you understand that you're beta testing and contributing to a brand new category of app.

GURU works best with Ollama Cloud models or local models. I personally currently use GLM 5.3 Flash because it's multimodal and relatively cheap to run. Any GLM model actually works beautifully within the framework, which leads me to believe Claude will work well in here too. I DID design the framework for people who own their own inference, so there's no token economy inside at all. So beware using GURU with LLMs from big tech. ChatGPT, Claude, xAI and Google are all yet untested within the framework, and honestly I do NOT recommend you use these models. THEY ARE NOT SECURE. Everything you do with these models gets published somewhere. I have just wired these in, and I can almost GUARANTEE you'll be paying hundreds if not thousands of pounds per month to run the model in this framework.

Instead, I highly recommend you find a server online, find a suitable model on Hugging Face or Ollama, and use that. 1m tokens of context is not required but very beneficial. You'll need to set up a Cloudflare tunnel on the server for the instance to reach your app. You can find some really good cheap servers on https://cloud.vast.ai. Failing that, you can use Ollama Cloud, the most cost effective choice. Ollama Cloud is almost a cheat code for the average Joe. They've got brilliant models to choose from, massive 2.8T multimodal LLMs, smaller agentic coders, and so on.

Once you've connected your model of choice, all you need to do is say hi and get to know your GURU.

I'll keep updating this document as I come across things I need to communicate to you all.

In any case, I'm excited to get this out.

If you have any questions you can contact me on steven@unuslumen.com

**Steven Newman** — Founder and Developer, Unus Lumen, Bristol UK
