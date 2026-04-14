# Rhapsody Component Importer (v1.0.1)

## Overview
This tool provides a programmatic bridge to **IBM Rhapsody 10.0.1** to resolve "Roundtripping" logic errors during C++ Reverse Engineering. By using the Rhapsody Java API, it bypasses the standard UI wizard to directly inject Classes, Attributes, and Operations into the model browser from header files. This code extends the Rhapsody Reverse Engineering tool which can sometimes cause Roundtripping and abruptly end the reverse engineering process.

## Credits & Contributors
Developed by the **Embedded Systems Development Team** at Visteon:
* **H. Reddy** (hreddy2@visteon.com) – Core logic and Rhapsody API integration
* **P. Parashi** (pparashi@visteon.com) – Technical validation, guide, implementation and testing
* **D. Gopinath** (dgopina1@visteon.com) – Management and Requirement alignment

## Prerequisites
* **IBM Rhapsody 10.0.1** (Open and active with a project loaded)
* **Java JDK 11+** (Tested with JDK 26)
* **Environment**: Read access to source headers (Supports Windows paths and `\\wsl.localhost\` network drives)

## Installation & Setup
1. Ensure `rhapsody.jar` is located at: 
   `C:\Program Files\IBM\Rhapsody\10.0.1\Share\JavaAPI\rhapsody.jar`
2. Add the above directory to your Windows **Path** environment variable to allow Java to find the native `rhapsody.dll`.

## Usage

### 1. Compile
Open a terminal in the script directory and run:

javac -cp ".;C:\Program Files\IBM\Rhapsody\10.0.1\Share\JavaAPI\rhapsody.jar" RhapsodyComponentImporter.java

### 2. Execute

java -Djava.library.path="C:\Program Files\IBM\Rhapsody\10.0.1\Share\JavaAPI" -cp ".;C:\Program Files\IBM\Rhapsody\10.0.1\Share\JavaAPI\rhapsody.jar" RhapsodyComponentImporter "<Header_Directory_Path>" "<Target_Package_Name>"

If using Powershell or VSCode terminal:

java "-Djava.library.path=C:\Program Files\IBM\Rhapsody\10.0.1\Share\JavaAPI" -cp ".;C:\Program Files\IBM\Rhapsody\10.0.1\Share\JavaAPI\rhapsody.jar" RhapsodyComponentImporter "<Header_Directory_Path>" "<Target_Package_Name>"