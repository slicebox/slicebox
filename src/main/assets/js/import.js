(function () {
   'use strict';
}());

angular.module('slicebox.import', ['ngRoute', 'ngFileUpload'])

.config(function($routeProvider) {
  $routeProvider.when('/import', {
    templateUrl: '/assets/partials/import.html',
    controller: 'ImportCtrl'
  });  
})

.controller('ImportCtrl', function($scope, Upload, $q, sbxToast, openAddEntityModal) {
    
    $scope.uiState.selectedSession = null;
    $scope.uiState.currentFileSet = {
        processing: false,
        index: 0,
        total: 0,
        progress: 0
    };

    $scope.callbacks = {};

    var importSessions = [];

    $scope.loadImportSessions = function(startIndex, count) {
        return importSessions;
    };

    $scope.addImportSessionButtonClicked = function() {
        openAddEntityModal(
            'addImportSessionModalContent.html', 
            'AddImportSessionModalCtrl', 
            '/api/imports', 
            'Import session', 
            $scope.callbacks.importSessionsTable)
        .then(function (importSession) {
            importSessions.push(importSession);
            $scope.callbacks.importSessionsTable.selectObject(importSession);
        });
    };

    $scope.importSessionSelected = function(importSession) {
        $scope.uiState.selectedSession = importSession;
    };

    function importFirst(files) {
        if (files && files.length) {
            $scope.uiState.currentFileSet.index++;
            $scope.uiState.currentFileSet.progress = Math.round(100 * $scope.uiState.currentFileSet.index / $scope.uiState.currentFileSet.total);
            Upload.upload({
                url: '/api/images',
                file: files[0]
            }).success(function (data, status, headers, config) {
                //importedFiles.push({ name: config.file.name });
                files.shift();
                importFirst(files);
            }).error(function (message, status, headers, config) {
                if (status >= 300 && status !== 400) {
                    sbxToast.showErrorMessage('Error importing file: ' + message);
                }
                files.shift();
                importFirst(files);
            });
        } else {
            $scope.uiState.currentFileSet.processing = false;
        }
    }

    $scope.import = function(files) {
        $scope.uiState.currentFileSet.processing = true;
        $scope.uiState.currentFileSet.index = 0;
        $scope.uiState.currentFileSet.total = files.length;
        $scope.uiState.currentFileSet.progress = 0;
        importFirst(files);
    };

})

.controller('AddImportSessionModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        return $mdDialog.hide({ 
            id: new Date().getTime(), // change to -1 later... 
            name: $scope.name,
            filesImported: 0, 
            filesRejected: 0,
            created: new Date().getTime(),
            lastUpdated: new Date().getTime()
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});