Kotlin/OpenCL balatro seed searcher

Heavily utilizes FP64 vector operations,
Will take full advantage of your gpu,
Runs best on MI300X / MI400X variants,
~80 million seeds per second with complex multi ante conditions on good hardware

Evententually i might rent some hardware for like 15$ and pick a bunch of conditions and search the entire balatro seed pool in an hour
Once amd releases their MI400 series cards this is gonna be crazy

If you wanna build it yourself you can make your own conditions in main(), there are some sample conditions already there.
I would recommend building a shadow jar so you only have to install java on whatever system you want to run it on, or just run it straight from ur ide
