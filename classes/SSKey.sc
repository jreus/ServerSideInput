/***********************************************
Server-side keyboard input hooks into sclang.
Allows callback functions to be attached to key actions
that operate even when SuperCollider is not the active application.

(C) 2015 Jonathan Reus / GPLv3

**********************************************/



/*
SSKey
A keylistener that responds OS-wide, even when SuperCollider is
not the key application.
The key listener won't respond to modifier key combinations..
shift, cmd, alt, ctrl are ignored.

@usage

s.boot;
SSKey.enable(s);



*/
SSKey {
	// OSX keysByCode and abbreviations
	classvar <keysByCode;
	classvar singleton;
	var <callbacks, <callbacks_up, <callbacks_down, <callback_keylogger;
	var <keysynths, <oscresponder, <responderKey;
	var <>verbose, <>keylogger_enabled;
	var <server;
	var <key_lastpressed, <>debounce_time;


	*enable {|serv|
		SSKey.getSingleton(serv).enable;
	}

	*disable {
		SSKey.getSingleton().disable;
	}

	*initClass {
		SSKey.initKeycodes;
	}

	init {|serv|
		callbacks = ();
		callbacks_up = ();
		callbacks_down = ();
		callback_keylogger = nil;
		verbose = false;
		keylogger_enabled = true;
		debounce_time = 0.08;
		key_lastpressed = ();
		server = serv;
		responderKey = ("/sskeyinput" ++ rrand(1,32000)).asSymbol;
	}

	*getSingleton {|serv=nil|
		if(singleton.isNil) {
			serv = serv ? Server.default;
			singleton = super.new.init(serv);
			CmdPeriod.add({
				SSKey.disable;
			});
			singleton.enable();
		};
		^singleton;
	}

	*addKeyResponder {|keysymbol, cb_func|
		SSKey.getSingleton().addKeyResponder(keysymbol,cb_func);
	}


	*doForKey {|keysymbol, state, function|
		SSKey.getSingleton().doForKey(keysymbol, state, function);
	}

	*enableKeylogger {|keylog_func=nil|
		SSKey.getSingleton().enableKeylogger(keylog_func);
	}

	*disableKeylogger {
		SSKey.getSingleton().disableKeylogger;
	}

	*setVerbose {|val|
		SSKey.getSingleton().verbose = val;
	}


	/******
	Enable Keylogging.
	keylog_func - The callback function which is called every time a key change is registered.

	*******/
	enableKeylogger {|keylog_func=nil|
		keylogger_enabled = true;
		if(keylog_func.notNil) {
			callback_keylogger = keylog_func;
		} {
			if(callback_keylogger.isNil) {
				callback_keylogger = {|state, symbol, index|
					Post << "KEYLOG //// " << "State: " << state << " Symbol: " << symbol << " Index: " << index << $\n;
				};
			};
		}
	}

	disableKeylogger {
		keylogger_enabled = false;
	}


	doForKey {|keysymbol, state, function|
		SSKey.addKeyResponder(keysymbol, {|val, symb, indx|
			if (val == 1) {
				if(callbacks_down[symb].isNil) {
					if(verbose) {
						("No response for keydown " ++ indx ++ " // " ++ symb).postln;
					};
				} {
					callbacks_down[symb].value(val, symb, indx);
				};
			} {
				if(callbacks_up[symb].isNil) {
					if(verbose) {
						("No response for keyup " ++ indx ++ " // " ++ symb).postln;
					};
				} {
					callbacks_up[symb].value(val, symb, indx);
				};
			};
		});

		if(state == 'UP') {
			callbacks_up.put(keysymbol, function);
		};

		if(state == 'DOWN') {
			callbacks_down.put(keysymbol, function);
		};

	}


	/* keysymbol - a valid symbol representing a key in keysByCode
	cb_func - a callback to perform when the given key is pressed/released, cb_func expects 1 argument, the key up/down value
	EX:
	{|val| if (val == 1) {"Key is down".postln;} {"Key is up".postln;}; }
	*/
	addKeyResponder {|keysymbol, cb_func|

		("Add responder for "++keysymbol).postln;
		if (keysByCode.findKeyForValue(keysymbol).isNil) {
			Error("Key symbol"+keysymbol+" not valid").throw;
		};

		("Found key for value: "+keysByCode.findKeyForValue(keysymbol)).postln;
		callbacks.isNil && {callbacks = ()};
		callbacks.put(keysymbol,cb_func);

		"CALLBACKS".postln;
		callbacks.postln;
	}

	// initialize synth and osc responder
	enable {
		if(this.isEnabled) {
			"Keyboard is already enabled".postln;
		} {
			// SC 3.5 has the Changed UGEN, here we use HPZ2 to detect keystate changes
			// NTS:: Look into how HPZ2 is working on the scope/plotter - I think it's causing double signals.
			Server.default.waitForBoot {
				keysynths = keysByCode.collect{|key, code|
					{
						var t_state = KeyState.kr(code,-1,1,lag:0);
						var hpz2 = HPZ2.kr(t_state);
						var t_tr = hpz2 > 0;
						SendReply.kr(t_tr, responderKey, [code, t_state]);
						0;
					}.play;
				};

				// OSCFunc available only in SC 3.5
				//oscresponder = OSCFunc({|msg| (" "+ msg + "  " + ~keysByCode[msg[2]]).postln; d[l[msg[2]]].value(msg[3].asInteger)}, \tr);

				// Here's the 3.4.4 compatible version
				oscresponder = OSCresponder(nil, responderKey, {|t, r, msg|
					var keysymbol, now, lastpressed, indx = msg[3].asInteger, state = msg[4].asInteger;
					keysymbol = keysByCode[indx];
					now = Process.elapsedTime;
					lastpressed = key_lastpressed[keysymbol] ? (now - debounce_time - 1);
					if((now - lastpressed) > debounce_time) {
						if(keylogger_enabled) {
							callback_keylogger.value(state, keysymbol, indx);
						};
						if(callbacks[keysymbol].isNil) {
							if(verbose) {
								("No response for key " ++ indx ++ " // " ++ keysByCode[indx]).postln;
							};
						} {
							callbacks[keysymbol].value(state, keysymbol, indx);
						};
					};
					key_lastpressed[keysymbol] = now;
				}).add;

				"Keyboard enabled".postln;
			};
		};
	}

	isEnabled { ^(keysynths.notNil && oscresponder.notNil); }

	// free all resources
	disable {
		keysynths.do {|synth|
			synth.free;
		};
		oscresponder.remove;
		keysynths = oscresponder = nil;
		"Keyboard disabled".postln;
	}

	*codesByKey {
		var cbk = Dictionary.new;
		keysByCode.keysValuesDo {|key,val|
			cbk.put(val, key);
		};
		^cbk;
	}

	*getKeycode {|keysymbol|
		var val;
		SSKey.initKeycodes;
		val = keysByCode.findKeyForValue(keysymbol);
		if (val.isNil) {
			Error("Key symbol"+keysymbol+" not valid").throw;
		};
		^val;
	}

	// http://macbiblioblog.blogspot.nl/2014/12/key-codes-for-function-and-special-keys.html
	*initKeycodes {
		var kbcMac, kbcLinux;
		kbcMac = (
				10:'sectionsign',
				18:'1',
				19:'2',
				20:'3',
				21:'4',
				23:'5',
				22:'6',
				26:'7',
				28:'8',
				25:'9',
				29:'0',
				27:'minus',
				24:'equal',
				51:'backspace',
				48:'tab',
				12:'q',
				13:'w',
				14:'e',
				15:'r',
				17:'t',
				16:'y',
				32:'u',
				34:'i',
				31:'o',
				35:'p',
				0:'a',
				1:'s',
				2:'d',
				3:'f',
				5:'g',
				4:'h',
				38:'j',
				40:'k',
				37:'l',
				6:'z',
				7:'x',
				8:'c',
				9:'v',
				11:'b',
				45:'n',
				46:'m',
				33:'leftbracket',
				30:'rightbracket',
				36:'return',
				53:'escape',
				76:'keypad_enter',
				51:'delete',
				41:'semicolon',
				39:'apost',
				42:'bkslsh',
				50:'tilde',
				43:'comma',
				47:'period',
				44:'fwdslsh',
				49:'space',
				123:'left',
				126:'up',
				125:'down',
				124:'right',
				63:'fn',
				122:'f1',
				120:'f2',
				99:'f3',
				118:'f4',
				96:'f5',
				97:'f6',
				98:'f7',
				100:'f8',
				101:'f9',
				109:'f10',
				103:'f11',
				111:'f12',
				105:'f13',
				55:'command',
				56:'shift',
				57:'capslock',
				58:'option',
				59:'control',
				60:'rightshift',
				61:'rightoption',
				62:'rightcontrol'

			);

		kbcLinux = (
			9:'escape',


			    49:'tilde',
			    10:'1',
				11:'2',
				12:'3',
				13:'4',
				14:'5',
				15:'6',
				16:'7',
				17:'8',
				18:'9',
				19:'0',
				20:'minus',
				21:'equal',
				22:'backspace',

			    23:'tab',
				24:'q',
				25:'w',
				26:'e',
				27:'r',
				28:'t',
				29:'y',
				30:'u',
				31:'i',
				32:'o',
				33:'p',
				34:'leftbracket',
				35:'rightbracket',
                51:'bkslsh',

			    38:'a',
				39:'s',
				40:'d',
				41:'f',
				42:'g',
				43:'h',
				44:'j',
				45:'k',
				46:'l',
			    47:'semicolon',
				48:'apost',
                36:'return',

			    50:'shift',
			    52:'z',
				53:'x',
				54:'c',
				55:'v',
				56:'b',
				57:'n',
				58:'m',
			    59:'comma',
				60:'period',
				61:'fwdslsh',
			    62:'rightshift',

				37:'control',
				64:'alt',
			    65:'space',
				113:'left',
				111:'up',
				116:'down',
				114:'right',
				108:'rightalt',
				105:'rightcontrol'



			);

		keysByCode ?? {
			Platform.case(
				\linux, {
				keysByCode = kbcLinux;
				},
				\osx, {
				keysByCode = kbcMac;
				}, {
				Error("Unknown OS setup for SSKey keycodes").throw;
				},
			);

		};
		^keysByCode;
	}

}