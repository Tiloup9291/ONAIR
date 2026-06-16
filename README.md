# ONAIR
Software-Defined Acoustic Modem for QAM messenging
![Message](/assets/images/message.png)
![Settings](/assets/images/settings.png)
## What is ONAIR?
ONAIR is the protocol and also the user interface implementation. It is a software-defined acoustic modem with QAM modulation operating in streaming mode and connectionless for messenging. The goal is to make it/keep it simple to communicate by air, with audio line IN and line OUT.
## ONAIR protocol frame
Total frame :
```
[SYNC!] | [ONAIR] | [Length] | [Flags] | [Payload] | [(Filter span + 2) alternating of QAM order diagonal corners]
```
Payload :
```
[Recipient] | [Sender] | [Message]
```
SYNC! : Preamble detection;<br>
ONAIR : Protocol magic number;<br>
Length : Length of the payload;<br>
Flags : Whether payload is encrypted or not;<br>
Recipient : Recipient to which the message is destined;<br>
Sender : Sender/Your local identification of the message;<br>
Message : Message to be send;<br>
(Filter span + 2) alternating of QAM order diagonal corners : Postamble of the frame;<br>
## How it works?
1. Both the recipient and the sender must have the same modulation settings.<br>
2. Only messages detected, for the protocol and for the recipient are demodulated and shown.<br>
3. (Optional) If encryption is use, both the recipient and the sender must have the same cypher key.<br>
4. To send message, you just need to click the "Send" button.<br>
5. To start receiving message, you must activate the "ON-AIR" button.<br>
6. A offline or test mode is available. It output the message to a WAV file for the sender. For the recipient, he can load a WAV file.<br>
7. In the settings tab, there is a "Acoustic Preset" button that set the modulation settings to known sure/safe working settings.<br>
## Pipeline
The modem detect the carrier. It then search the preambule to synchronize the frame. Then it search for the protocol magic number to be sure the frame match the protocol. After that, it use the length and flags fields to know how to demodulate the payload. Once the payload is managed, it compare the recipient field in the frame to the local sender name. If it match, the sender field and message are shown in the interface. The postamble is not counted in the length of the payload; its only use is to border the frame in the state ring-buffer of the demodulator.
## Things to consider
1. The quality of the user audio interface matters.<br>
2. The quality of the line IN and line OUT matters.<br>
3. The quality of devices matters (Example : speakers and microphones).<br>
4. The quality of the transportation media matters (Example : air).<br>
5. Environment noise impact demodulation. Higher noise = harder to demodulate.<br>
6. Depending of drivers, OS and devices, disable all automatic sound processing features (Example : AGC, noise reduction, echo reduction).<br>
### Technologies included
1. FFT and squelch carrier detector.<br>
2. Correlation detector.<br>
3. Costas Loop.<br>
4. Gardner Timing Recovery.<br>
5. RRC filter.<br>
6. FIR filter.<br>
7. QAM order from 2 to 65536.<br>
8. Constellation in Gray Code.<br>
9. State ring-buffer.<br>
10. Phase-Locked Loop.<br>
