<?php

require "commons.inc.php";

// Debug stuff
// if (isset($_REQUEST['inputRadio']) && $_REQUEST['inputRadio']) {
// 	echo "<pre>";
// 	print_r($_REQUEST);
// 	print_r($_FILES);
// 	echo "</pre>";
// 	exit();
// }

ob_start();

$in = $out = "";
$go = false;

// Security stuff
$pars = array();
$pars['inputRadio'] = isset($_REQUEST['inputRadio']) ? $_REQUEST['inputRadio'] : null;
$pars['readText'] = isset($_REQUEST['readText']) ? $_REQUEST['readText'] : "";
$pars['readTextType'] = isset($_REQUEST['readTextType']) ? preg_replace("/[^a-z0-9]/i", "", $_REQUEST['readTextType']) : "";
$pars['fileType'] = isset($_REQUEST['fileType']) ? preg_replace("/[^a-z0-9]/i", "", $_REQUEST['fileType']) : "";
$pars['fileCompression'] = isset($_REQUEST['fileCompression']) ? preg_replace("/[^a-z0-9]/i", "", $_REQUEST['fileCompression']) : "";
$pars['showResults'] = isset($_REQUEST['showResults']) && $_REQUEST['showResults'];
$pars['commands'] = isset($_REQUEST['commands']) ? $_REQUEST['commands'] : "";

if ($pars['inputRadio'] != null) {
	if ($pars['inputRadio'] == "file") {
		try {
			if ($_FILES['readFile']['error'] !== UPLOAD_ERR_OK) { 
				throw new UploadException($_FILES['readFile']['error']); 
			}

			$in = dirname($_FILES['readFile']['tmp_name'])."/".$_FILES['readFile']['name'];
			$out = $_FILES['readFile']['tmp_name']."-out";

			move_uploaded_file($_FILES['readFile']['tmp_name'], $in);

			$go = true;
		}
		catch (Exception $e) {
			echo '<div class="alert alert-danger" role="alert">', $e->getMessage(), '</div>';
		}
	}
	elseif ($pars['inputRadio'] == "text") {
		$in = tempnam("/tmp", "FOO");
		$out = "{$in}-out";
		file_put_contents($in, $pars['readText']);
		$in = ".{$pars['readTextType']}:$in";
		$go = true;
	}
	else {
		$in = "$F/custom/$inputExample";
		$out = tempnam(sys_get_temp_dir(), "reasoning-out");
		$go = true;
	}
}

if ($go) {

	for ($i = 1; $i <= $additionalFileNo; $i++) {
		if (isset($_FILES['additionalFile'.$i]) && $_FILES['additionalFile'.$i]['error'] === UPLOAD_ERR_OK) {
			$pars['commands'] = str_replace("#file".$i, $_FILES['additionalFile'.$i]['tmp_name'], $pars['commands']);
		}
	}

	foreach ($customFiles as $key => $value) {
		$key = preg_replace("/[^a-z0-9]/i", "", $key);
		$pars['commands'] = str_replace("#".$key, "$customFolder/$value", $pars['commands']);
	}

	$outExt = $pars['fileType'];
	if ($pars['fileCompression'] && !$pars['showResults']) {
		$outExt .= ".{$pars['fileCompression']}";
	}
	$out .= ".$outExt";

	$pars['commands'] = escapeshellcmd($pars['commands']);

	$rdfp_command = "$rdfpro_path @read $in {$pars['commands']} @write $out";

	// Debug stuff
	// echo $rdfp_command;
	// exit(1);

	$log = shell_exec($rdfp_command . " 2>&1 1> /dev/null");

	if (!trim($log)) {
		if ($pars['showResults']) {
		    header("Content-Type: text/plain");
		    readfile($out);
		}
		else {
			$baseOut = basename($out);

		    header("Cache-Control: public");
		    header("Content-Description: File Transfer");
		    header("Content-Disposition: attachment; filename=$baseOut");
		    header("Content-Transfer-Encoding: binary");
		    header("Content-Type: application/octet-stream");

		    readfile($out);
		}

	    exit();
	}

	// Debug stuff
	// $log = "$rdfp_command\n\n$log";

	echo '<div class="alert alert-danger" role="alert">', nl2br($log), '</div>';

}

$Text = ob_get_clean();

include "template.inc.php";
