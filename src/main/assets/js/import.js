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

.controller('ImportCtrl', function($scope, Upload, $q, sbxToast) {
    
    $scope.uiState.selectedSession = null;

    $scope.callbacks = {};

    var importSessions = [];

    $scope.loadImportSessions = function(startIndex, count) {
        return importSessions;
    };

    $scope.newImportSessionButtonClicked = function() {
        $scope.callbacks.importSessionsTable.clearSelection();
        
        $scope.uiState.selectedImportSession = { 
            id: -1, 
            name: undefined,
            created: new Date().getTime(), 
            filesImported: 0, 
            filesRejected: 0 
        };
    };

    $scope.importSessionSelected = function(importSession) {
        $scope.uiState.selectedSession = importSession;
    };

    function importFirst(files) {
        if (files && files.length) {
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
        }
    }

    $scope.import = function(files) {
        importFirst(files);
    };

})

.controller('ImportSessionCtrl', function($scope, $http, $mdDialog, $q, sbxToast) {
    // Initialization

    $scope.createButtonClicked = function () {
        var savePromise;

        if ($scope.importSessionForm.$invalid) {
            return;
        }

        if ($scope.uiState.selectedImportSession.id === -1) {
            savePromise = $http.post('/api/importsessions', $scope.uiState.selectedImportSession);
        }
        
        savePromise = savePromise.then(function(response) {
            if (response.data.id) {
                $scope.uiState.selectedImportSession.id = response.data.id;
            }
            
        });

        savePromise.then(function() {
            sbxToast.showInfoMessage("Import session created");
            $scope.callbacks.importSessionsTable.reloadPage();
        }, function(error) {
            sbxToast.showErrorMessage(error);
        });

        return savePromise;
    };

});
