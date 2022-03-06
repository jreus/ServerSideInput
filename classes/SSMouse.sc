/***********************************************
Server-side mouse input hooks into the language.
Allows attaching callback functions to mouse events that are
detected even when SuperCollider is not the active application.

(C) 2015 Jonathan Reus / GPLv3

**********************************************/


/***************************************************************
SSMouse
A mouse input listener that responds OS-wide, even when SuperCollider is not the key application.

@usage

s.boot;
SSMouse.enabled = true;
SSMouse.setMouseResponder(\button, {|val| if(val==1) { "down".postln } { "up".postln }} );
SSMouse.setMouseResponder(\move, {|x,y| "X %  Y %".postf(x,y) } );

***************************************************************/
SSMouse {
	classvar singleton;
	var callbacks,listenersynth,oscresponder,responderKey;


	*enabled_ {|bool|
    if(bool)
      { SSMouse.getSingleton.enable }
      { SSMouse.getSingleton.disable };
	}

  *enabled { ^SSMouse.getSingleton.isEnabled }


	initme {|server|
		this.initCallbacks;
		responderKey = ("/SSMouse" ++ rrand(1,32000)).asSymbol;
	}

	*getSingleton {
		if(singleton.isNil) {
			singleton = super.new.initme(Server.default);
			CmdPeriod.add({
				SSMouse.disable;
			});
		};
		^singleton;
	}

	*setMouseResponder {|mouseaction, cb_func|
		SSMouse.getSingleton.setMouseResponder(mouseaction,cb_func);
	}

	/*
  @param mouseaction a mouse function \move or \button
	@param cb_func callback to perform
	 \move has 2 args, xpos & ypos
	 \button has 1 argument, the button up/down value
	EX:
	{|val| if (val == 1) {"Mouse is down".postln;} {"Mouse is up".postln;}; }
	*/
	setMouseResponder {|mouseaction, cb_func|
		if (callbacks.includesKey(mouseaction).not) {
			Error("Mouse action"+mouseaction+" not valid").throw;
		};
		callbacks.put(mouseaction,cb_func);
	}

  pr_checkServerAlive{|server, errFunc|
    if(server.isNil) { server=Server.default };
    if(server.serverRunning.not) {
      "Cannot enable SSMouse. Boot the server first!".error;
      errFunc.();
    }
  }

	// initialize synth and osc responder
	enable {|server|
		if(this.isEnabled) {
			"SSMouse is already enabled".warn;
		} {
			// SC 3.5 has the Changed UGEN, here we use HPZ2 to detect keystate changes
      this.pr_checkServerAlive(server, {^nil});
			listenersynth = {
				var xsig = MouseX.kr(0,1,lag:0);
				var ysig = MouseY.kr(0,1,lag:0);
				var bsig = MouseButton.kr(-1,1,lag:0);
				var xhpz = HPZ2.kr(xsig) > 0;
				var yhpz = HPZ2.kr(ysig) > 0;
				var bhpz = HPZ2.kr(bsig) > 0;
				SendReply.kr(xhpz | yhpz | bhpz, responderKey, [xhpz,yhpz,bhpz,xsig,ysig,bsig]);
			}.play;

      oscresponder = OSCFunc.new({|msg|
				var movex=msg[3],movey=msg[4],button=msg[5];
				var xpos=msg[6],ypos=msg[7],bstate=msg[8];

				if (movex.asBoolean.or { movey.asBoolean }) {
					callbacks['move'].value(xpos.round(0.00001), ypos.round(0.00001));
				};
				if (button.asBoolean) {
					callbacks['button'].value(bstate);
				};
			},responderKey);

			"SSMouse enabled".postln;
		};
	}

	isEnabled { ^(listenersynth.notNil && oscresponder.notNil); }

	// free all resources
	disable {
		listenersynth.free;
		oscresponder.free;
		listenersynth = oscresponder = nil;
		"SSMouse disabled".postln;
	}

	initCallbacks {
		callbacks ?? {
			callbacks = (
				'move':{|x,y| "MOUSEPOS % %\n".postf(x,y)},
        'button':{|b| "MOUSEBUTTON %\n".postf(b)}
			);
		};
	}

}


